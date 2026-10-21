package site.qgames.games.api.session

import site.qgames.games.api.Decision
import site.qgames.games.api.GameRules
import site.qgames.games.api.TimeControl
import site.qgames.games.api.core.CoreModule
import site.qgames.games.api.core.PlayerState
import site.qgames.games.api.timer.TimerModule
import site.qgames.games.api.timer.TimerState
import site.qgames.games.api.turns.TurnsModule
import site.qgames.games.api.turns.TurnsState
import java.time.Instant
import java.util.UUID

/**
 * Платформа партии: собирает игру с модулями core, turns и timer. Чистые функции, без IO: сервис game отвечает за
 * журнал, блокировки и рассылку, а перевод в JSON-дерево даёт [codec].
 *
 * - [start] — первое событие журнала `session-started` (`PUT [] {status, meta, state}`);
 * - [move], [timeout], [pause], [resume], [cancel] — события изменения по полному документу партии;
 * - состояние — свёртка событий общим применителем [SessionPatch] ([fold]) и для полного документа, и для проекций
 *   (маскированные события): снапшот зрителя — свёртка его маскированных событий;
 * - [mask] — событие для проекции зрителя (маскируется только значение);
 * - [read] — типизированное чтение документа.
 *
 * Все события, которые отдаёт платформа, уже со значением-деревом (как в журнале и у клиента). После событий игры
 * платформа сама добавляет `move-options-changed` игрокам, у которых изменились варианты ходов, и `timer-started` с
 * дедлайном следующего хода. Перед концом партии (`session-finished` игры, отмена) таймер останавливается
 * (`timer-stopped`), а событие конца уходит последним.
 */
class SessionEngine(private val codec: SessionCodec) {

    /** Первое событие журнала: описание партии и начальное состояние с вариантами ходов и таймером. */
    fun start(
        rules: GameRules<Any, Any, Any>,
        players: List<UUID>,
        seed: Long,
        settings: Any,
        timeControl: TimeControl,
        now: Instant,
    ): List<SessionEvent> {
        val start = rules.start(players, seed, settings)
        val initial = SessionState(CoreModule.initialPlayers(players), TimerModule.initial(timeControl, now), start.turns, start.game)
        val state = initial.copy(players = initial.players.map { it.copy(moveOptions = rules.moveOptions(initial, it.id)) })
        val gameSettings = linkedMapOf<String, Any?>(GAME_CODE to rules.code).apply { putAll(objectTree(settings)) }
        val meta = SessionMeta(GameMeta(rules.code, SessionRules(timeControl, gameSettings)))
        val document = linkedMapOf(STATUS to SessionStatus.IN_PROGRESS, META to meta, SessionEvents.STATE to stateTree(state))
        return listOf(tree(SessionEvents.put(SessionEvents.SESSION_STARTED, emptyList(), document)))
    }

    /** Ход игрока [playerId] по полному документу [document]. */
    fun move(rules: GameRules<Any, Any, Any>, document: SessionDocument, playerId: UUID, move: Any, now: Instant): MoveOutcome {
        val session = document.session
        return when {
            session.status != SessionStatus.IN_PROGRESS -> MoveOutcome.NotInProgress
            TimerModule.isPaused(session.state.timer) -> MoveOutcome.Paused
            else -> when (val decision = rules.decide(session.state, playerId, move)) {
                is Decision.Rejected -> MoveOutcome.Rejected(decision.code, decision.message)
                is Decision.Accepted -> MoveOutcome.Accepted(complete(rules, document, decision.events, now))
            }
        }
    }

    /** Время хода истекло: последствия решает игра. Пустой список — ничего не происходит. */
    fun timeout(rules: GameRules<Any, Any, Any>, document: SessionDocument, now: Instant): List<SessionEvent> {
        val session = document.session
        if (session.status != SessionStatus.IN_PROGRESS || TimerModule.isPaused(session.state.timer)) return emptyList()
        val events = rules.onTimeout(session.state)
        return if (events.isEmpty()) emptyList() else complete(rules, document, events, now)
    }

    /** Пауза — действие таймера (`game-paused`). */
    fun pause(document: SessionDocument, now: Instant): TimerOutcome = timerAction(document) { TimerModule.pause(it, now) }

    /** Продолжение (`game-resumed`): дедлайн хода сдвигается на длительность паузы. */
    fun resume(document: SessionDocument, now: Instant): TimerOutcome = timerAction(document) { TimerModule.resume(it, now) }

    /**
     * Отмена: варианты ходов всех игроков обнуляются (`move-options-changed`, как на конце партии), таймер (если есть)
     * останавливается, затем `session-cancelled`.
     */
    fun cancel(document: SessionDocument): List<SessionEvent> {
        val state = document.session.state
        val events = CoreModule.changes(state) { emptyList() } + listOfNotNull(state.timer?.let(TimerModule::stop)) + SessionEvents.cancelled()
        return events.map(::tree)
    }

    /** Событие для проекции [viewer]: закрытое для зрителя значение заменяется маской, но событие доставляется. */
    fun mask(rules: GameRules<Any, Any, Any>, event: SessionEvent, viewer: ViewerKey): SessionEvent {
        if (viewer == ViewerKey.Full) return event
        return when (event.code) {
            SessionEvents.SESSION_STARTED -> event.copy(value = maskStart(rules, objectOf(event.value), viewer))
            CoreModule.MOVE_OPTIONS_CHANGED -> CoreModule.mask(event, viewer)!!
            in PUBLIC_CODES -> event
            else -> tree(rules.mask(event, viewer))
        }
    }

    /** Типизированное чтение полного документа партии (проверка ходов, таймер, статус). */
    fun read(rules: GameRules<Any, Any, Any>, document: Map<String, Any?>): SessionDocument {
        val state = objectOf(document[SessionEvents.STATE])
        val session = Session(
            meta = codec.fromTree(document[META], SessionMeta::class.java),
            status = SessionStatus.valueOf(document[STATUS] as String),
            state = SessionState(
                players = (state[CoreModule.PLAYERS] as List<*>).map { codec.fromTree(it, PlayerState::class.java) },
                timer = state[TimerModule.TIMER]?.let { codec.fromTree(it, TimerState::class.java) },
                turns = codec.fromTree(state[TurnsModule.TURNS], TurnsState::class.java),
                game = codec.fromTree(state - MODULE_KEYS, rules.gameStateClass),
            ),
        )
        return SessionDocument(document, session)
    }

    /** Дописывает к событиям игры события модулей: варианты ходов, таймер следующего хода; конец партии — последним. */
    private fun complete(
        rules: GameRules<Any, Any, Any>,
        document: SessionDocument,
        gameEvents: List<SessionEvent>,
        now: Instant,
    ): List<SessionEvent> {
        val (ending, own) = gameEvents.map(::tree).partition { it.code == SessionEvents.SESSION_FINISHED }
        val finished = ending.isNotEmpty()
        val after = read(rules, fold(document.tree, own)!!).session
        val players = CoreModule.changes(after.state) { if (finished) emptyList() else rules.moveOptions(after.state, it) }
        val timer = if (finished) {
            after.state.timer?.let(TimerModule::stop)
        } else {
            TimerModule.restart(document.session.meta.game.rules.timeControl, now)
        }
        return own + (players + listOfNotNull(timer) + ending.take(1)).map(::tree)
    }

    private fun timerAction(document: SessionDocument, change: (TimerState) -> SessionEvent?): TimerOutcome {
        val session = document.session
        if (session.status != SessionStatus.IN_PROGRESS) return TimerOutcome.Unchanged
        val timer = session.state.timer ?: return TimerOutcome.NotPausable
        val event = change(timer) ?: return TimerOutcome.Unchanged
        return TimerOutcome.Changed(listOf(tree(event)))
    }

    /** Начальный документ для [viewer]: чужие варианты ходов скрыты, игровая часть — по маске игры. */
    private fun maskStart(rules: GameRules<Any, Any, Any>, document: Map<String, Any?>, viewer: ViewerKey): Map<String, Any?> {
        val state = objectOf(document[SessionEvents.STATE])
        val players = (state[CoreModule.PLAYERS] as List<*>).map { CoreModule.mask(codec.fromTree(it, PlayerState::class.java), viewer) }
        val game = rules.maskGame(codec.fromTree(state - MODULE_KEYS, rules.gameStateClass), viewer)
        val masked = linkedMapOf(
            CoreModule.PLAYERS to codec.toTree(players),
            TimerModule.TIMER to state[TimerModule.TIMER],
            TurnsModule.TURNS to state[TurnsModule.TURNS],
        ).apply { putAll(objectTree(game)) }
        return LinkedHashMap(document).apply { put(SessionEvents.STATE, masked) }
    }

    /** Состояние в документе: блоки модулей, рядом — поля игры. */
    private fun stateTree(state: SessionState<Any>): Map<String, Any?> {
        val game = objectTree(state.game)
        val clash = game.keys.filter { it in MODULE_KEYS }
        check(clash.isEmpty()) { "Game state fields $clash clash with the module blocks $MODULE_KEYS" }
        return linkedMapOf(
            CoreModule.PLAYERS to codec.toTree(state.players),
            TimerModule.TIMER to codec.toTree(state.timer),
            TurnsModule.TURNS to codec.toTree(state.turns),
        ).apply { putAll(game) }
    }

    private fun tree(event: SessionEvent): SessionEvent = event.copy(value = codec.toTree(event.value))

    private fun objectTree(value: Any): Map<String, Any?> = objectOf(codec.toTree(value))

    @Suppress("UNCHECKED_CAST")
    private fun objectOf(tree: Any?): Map<String, Any?> =
        tree as? Map<String, Any?> ?: error("Expected a JSON object, got ${tree?.let { it::class.qualifiedName }}")

    companion object {
        const val STATUS = "status"
        const val META = "meta"

        /** Поле настроек игры в meta.game.rules.gameSettings с кодом игры (как в настройках комнаты). */
        const val GAME_CODE = "gameCode"

        /** Блоки модулей в состоянии; остальные поля состояния — игровые. */
        val MODULE_KEYS = setOf(CoreModule.PLAYERS, TimerModule.TIMER, TurnsModule.TURNS)

        /** Коды публичных событий платформы и модулей: маски нет. */
        private val PUBLIC_CODES = setOf(
            SessionEvents.SESSION_FINISHED,
            SessionEvents.SESSION_CANCELLED,
            TurnsModule.TURN_CHANGED,
            TimerModule.TIMER_STARTED,
            TimerModule.GAME_PAUSED,
            TimerModule.GAME_RESUMED,
            TimerModule.TIMER_STOPPED,
        )

        /** Свёртка событий общим применителем; [document] null — журнал с начала. */
        fun fold(document: Map<String, Any?>?, events: List<SessionEvent>): Map<String, Any?>? = SessionPatch.fold(document, events)
    }
}

sealed interface MoveOutcome {

    data class Accepted(val events: List<SessionEvent>) : MoveOutcome

    /** Отказ правил игры, [code] — машиночитаемая причина. */
    data class Rejected(val code: String, val message: String) : MoveOutcome

    /** Таймер на паузе: ходы запрещены. */
    data object Paused : MoveOutcome

    data object NotInProgress : MoveOutcome
}

sealed interface TimerOutcome {

    data class Changed(val events: List<SessionEvent>) : TimerOutcome

    /** Таймер уже в нужном состоянии (повторная пауза, продолжение без паузы). */
    data object Unchanged : TimerOutcome

    /** У партии нет таймера: паузы нет. */
    data object NotPausable : TimerOutcome
}
