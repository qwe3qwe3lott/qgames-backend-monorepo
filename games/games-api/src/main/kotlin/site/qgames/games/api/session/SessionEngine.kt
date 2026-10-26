package site.qgames.games.api.session

import site.qgames.games.api.Decision
import site.qgames.games.api.GameRules
import site.qgames.games.api.TimeControl
import site.qgames.games.api.core.CoreModule
import site.qgames.games.api.core.PlayerState
import site.qgames.games.api.result.ResultModule
import site.qgames.games.api.result.ResultState
import site.qgames.games.api.timer.TimerModule
import site.qgames.games.api.timer.TimerState
import site.qgames.games.api.turns.TurnsModule
import site.qgames.games.api.turns.TurnsState
import java.time.Instant
import java.util.UUID

/**
 * Платформа партии: собирает игру с модулями core, turns, timer и result. Чистые функции, без IO: сервис game отвечает
 * за журнал, блокировки и рассылку, а перевод в JSON-дерево даёт [codec].
 *
 * - [start] — первое событие журнала `session-started` (`PUT [] {status, meta, state}`);
 * - [move], [timeout], [pause], [resume], [cancel] — события изменения по полному документу партии;
 * - состояние — свёртка событий общим применителем [SessionPatch] ([fold]) и для полного документа, и для проекций
 *   (маскированные события): снапшот зрителя — свёртка его маскированных событий;
 * - [mask] — событие для проекции зрителя (маскируется только значение);
 * - [read] — типизированное чтение документа.
 *
 * Все события, которые отдаёт платформа, уже со значением-деревом (как в журнале и у клиента). После событий игры
 * платформа сама добавляет `move-options-changed` игрокам, у которых изменились варианты ходов, и `clock-switched`, если
 * сменилась очередь (часы ходившего и следующего). Перед концом партии (`session-finished` игры, отмена) таймер останавливается
 * (`timer-stopped`), а событие конца уходит последним. Итог партии (`result-set`) игра ставит только вместе с
 * `session-finished`.
 *
 * Поля игры у игрока ([PlayerState.game]) лежат в документе прямо в объекте игрока рядом с полями модуля core.
 */
class SessionEngine(private val codec: SessionCodec) {

    /** Первое событие журнала: описание партии и начальное состояние с вариантами ходов и таймером. */
    fun start(
        rules: GameRules<Any, Any, Any>,
        players: List<UUID>,
        seed: Long,
        settings: Any,
        timeControl: TimeControl?,
        now: Instant,
    ): List<SessionEvent> {
        val start = rules.start(players, seed, settings)
        checkStartPlayers(rules, players, start.players)
        val initial = SessionState(
            players = CoreModule.initialPlayers(players, start.players),
            timer = TimerModule.initial(timeControl, players, start.turns.currentPlayerId, now),
            turns = start.turns,
            result = null,
            game = start.game,
        )
        val state = initial.copy(players = initial.players.map { it.copy(moveOptions = rules.moveOptions(initial, it.id)) })
        val gameSettings = linkedMapOf<String, Any?>(GAME_CODE to rules.code).apply { putAll(objectTree(settings)) }
        val meta = SessionMeta(GameMeta(rules.code, SessionRules(timeControl, gameSettings)), codec.toTree(rules.moveRules(settings)))
        val document = linkedMapOf(STATUS to SessionStatus.IN_PROGRESS, META to meta, SessionEvents.STATE to stateTree(state))
        return listOf(tree(SessionEvents.put(SessionEvents.SESSION_STARTED, emptyList(), document)))
    }

    /** Ход игрока [playerId] по полному документу [document]. */
    fun move(rules: GameRules<Any, Any, Any>, document: SessionDocument, playerId: UUID, move: Any, now: Instant): MoveOutcome {
        val session = document.session
        return when {
            session.status != SessionStatus.IN_PROGRESS -> MoveOutcome.NotInProgress
            TimerModule.isPaused(session.state.timer) -> MoveOutcome.Paused
            else -> when (val decision = rules.decide(session.state, settings(rules, session), playerId, move)) {
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
     * останавливается, затем `session-cancelled`. Итога у отменённой партии нет.
     */
    fun cancel(document: SessionDocument, now: Instant): List<SessionEvent> {
        val state = document.session.state
        val timer = state.timer?.let { TimerModule.stop(it, state.turns.currentPlayerId, now) }
        val events = CoreModule.changes(state) { emptyList() } + listOfNotNull(timer) + SessionEvents.cancelled()
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
                players = (state[CoreModule.PLAYERS] as List<*>).map { readPlayer(rules, it) },
                timer = state[TimerModule.TIMER]?.let { codec.fromTree(it, TimerState::class.java) },
                turns = codec.fromTree(state[TurnsModule.TURNS], TurnsState::class.java),
                result = state[ResultModule.RESULT]?.let { codec.fromTree(it, ResultState::class.java) },
                game = codec.fromTree(state - MODULE_KEYS, rules.gameStateClass),
            ),
        )
        return SessionDocument(document, session)
    }

    /**
     * Дописывает к событиям игры события модулей: варианты ходов, переключение часов при смене очереди; конец партии —
     * последним.
     * Итог партии (`result-set`) допускается только вместе с концом партии.
     */
    private fun complete(
        rules: GameRules<Any, Any, Any>,
        document: SessionDocument,
        gameEvents: List<SessionEvent>,
        now: Instant,
    ): List<SessionEvent> {
        val (ending, own) = gameEvents.map(::tree).partition { it.code == SessionEvents.SESSION_FINISHED }
        val finished = ending.isNotEmpty()
        val results = own.count { it.code == ResultModule.RESULT_SET }
        check(results == 0 || finished) { "${ResultModule.RESULT_SET} is allowed only together with ${SessionEvents.SESSION_FINISHED}" }
        check(results <= 1) { "${ResultModule.RESULT_SET} must be set once, got $results" }
        val after = read(rules, fold(document.tree, own)!!).session
        val players = CoreModule.changes(after.state) { if (finished) emptyList() else rules.moveOptions(after.state, it) }
        val previous = document.session.state.turns.currentPlayerId
        val timer = if (finished) {
            after.state.timer?.let { TimerModule.stop(it, previous, now) }
        } else {
            TimerModule.switch(after.state.timer, document.session.meta.game.rules.timeControl, previous, after.state.turns.currentPlayerId, now)
        }
        return own + (players + listOfNotNull(timer) + ending.take(1)).map(::tree)
    }

    /** Настройки игры партии (GameRules.settingsClass) из `meta.game.rules.gameSettings` без кода игры. */
    private fun settings(rules: GameRules<Any, Any, Any>, session: Session): Any =
        codec.fromTree(session.meta.game.rules.gameSettings - GAME_CODE, rules.settingsClass)

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
        val players = (state[CoreModule.PLAYERS] as List<*>).map { playerTree(CoreModule.mask(readPlayer(rules, it), viewer)) }
        val game = rules.maskGame(codec.fromTree(state - MODULE_KEYS, rules.gameStateClass), viewer)
        val masked = linkedMapOf(
            CoreModule.PLAYERS to players,
            TimerModule.TIMER to state[TimerModule.TIMER],
            TurnsModule.TURNS to state[TurnsModule.TURNS],
            ResultModule.RESULT to state[ResultModule.RESULT],
        ).apply { putAll(objectTree(game)) }
        return LinkedHashMap(document).apply { put(SessionEvents.STATE, masked) }
    }

    /** Состояние в документе: блоки модулей, рядом — поля игры. */
    private fun stateTree(state: SessionState<Any>): Map<String, Any?> {
        val game = objectTree(state.game)
        val clash = game.keys.filter { it in MODULE_KEYS }
        check(clash.isEmpty()) { "Game state fields $clash clash with the module blocks $MODULE_KEYS" }
        return linkedMapOf(
            CoreModule.PLAYERS to state.players.map(::playerTree),
            TimerModule.TIMER to codec.toTree(state.timer),
            TurnsModule.TURNS to codec.toTree(state.turns),
            ResultModule.RESULT to codec.toTree(state.result),
        ).apply { putAll(game) }
    }

    /** Объект игрока в документе: поля модуля core, рядом — поля игры. */
    private fun playerTree(player: PlayerState): Map<String, Any?> {
        val core = linkedMapOf<String, Any?>("id" to codec.toTree(player.id), "moveOptions" to codec.toTree(player.moveOptions))
        val game = player.game?.let(::objectTree).orEmpty()
        val clash = game.keys.filter { it in PlayerState.FIELDS }
        check(clash.isEmpty()) { "Game player fields $clash clash with the core player fields ${PlayerState.FIELDS}" }
        return core.apply { putAll(game) }
    }

    /** Игрок из документа: поля модуля core и поля игры (GameRules.playerClass). */
    private fun readPlayer(rules: GameRules<Any, Any, Any>, tree: Any?): PlayerState {
        val player = objectOf(tree)
        val core = codec.fromTree(player.filterKeys { it in PlayerState.FIELDS }, PlayerState::class.java)
        val fields = player - PlayerState.FIELDS
        val playerClass = rules.playerClass
        if (playerClass == null) {
            check(fields.isEmpty()) { "Game ${rules.code} has no player fields, got ${fields.keys}" }
            return core
        }
        return core.copy(game = codec.fromTree(fields, playerClass))
    }

    /** Поля игры у игроков из [GameRules.start] — у каждого игрока партии и класса GameRules.playerClass. */
    private fun checkStartPlayers(rules: GameRules<Any, Any, Any>, players: List<UUID>, fields: Map<UUID, Any>) {
        val playerClass = rules.playerClass
        if (playerClass == null) {
            check(fields.isEmpty()) { "Game ${rules.code} declares no player class, but sets player fields" }
            return
        }
        check(fields.keys == players.toSet()) { "Game ${rules.code} must set player fields for every player" }
        check(fields.values.all(playerClass::isInstance)) { "Player fields of game ${rules.code} must be ${playerClass.name}" }
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
        val MODULE_KEYS = setOf(CoreModule.PLAYERS, TimerModule.TIMER, TurnsModule.TURNS, ResultModule.RESULT)

        /** Коды публичных событий платформы и модулей: маски нет. */
        private val PUBLIC_CODES = setOf(
            SessionEvents.SESSION_FINISHED,
            SessionEvents.SESSION_CANCELLED,
            TurnsModule.TURN_CHANGED,
            TimerModule.CLOCK_SWITCHED,
            TimerModule.GAME_PAUSED,
            TimerModule.GAME_RESUMED,
            TimerModule.TIMER_STOPPED,
            ResultModule.RESULT_SET,
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
