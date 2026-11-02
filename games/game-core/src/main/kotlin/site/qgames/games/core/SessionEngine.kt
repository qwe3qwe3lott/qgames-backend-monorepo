package site.qgames.games.core

import site.qgames.common.docpatch.DocumentPatch
import java.time.Instant
import java.util.UUID

/**
 * Платформа партии: ведёт блоки core (стадия, игроки, очерёдность, часы, итог) вокруг правил игры. Чистые функции, без
 * IO: сервис game отвечает за журнал, блокировки и рассылку, а перевод в JSON-дерево даёт [codec].
 *
 * - [start] — первое событие журнала `session-started` (`PUT [] {status, meta, state}`);
 * - [move], [timeout], [pause], [resume], [cancel] — события изменения по полному документу партии;
 * - состояние — свёртка событий общим применителем DocumentPatch ([fold]) и для полного документа, и для проекций
 *   (маскированные события): снапшот зрителя — свёртка его маскированных событий;
 * - [mask] — событие для проекции зрителя (маскируется только значение);
 * - [read] — типизированное чтение документа.
 *
 * Все события, которые отдаёт платформа, уже со значением-деревом (как в журнале и у клиента). После событий игры
 * платформа сама добавляет `move-options-changed` игрокам, у которых изменились варианты ходов, и `clock-switched`, если
 * сменилась очередь (часы ходившего и следующего). Переход стадии игры ([Stages.changed]) платформа разворачивает в
 * `stage-changed` = `PUT ["state"]` полного состояния новой стадии и ставит её часы (`timer-started`). Перед концом
 * партии (`session-finished` игры, отмена) таймер останавливается (`timer-stopped`), а событие конца уходит последним.
 * Итог партии (`result-set`) игра ставит только вместе с `session-finished`.
 *
 * Поля игры у игрока ([PlayerState.game]) лежат в документе прямо в объекте игрока рядом с полями core.
 */
class SessionEngine(private val codec: SessionCodec) {

    /**
     * Первое событие журнала: описание партии и начальное состояние первой стадии с вариантами ходов и таймером.
     * [settings] — проверенные advanced-настройки (GameRules.advancedClass).
     */
    fun start(
        rules: GameRules<Any, Any, Any, Any>,
        players: List<UUID>,
        seed: Long,
        settings: Any,
        now: Instant,
    ): List<SessionEvent> {
        val start = rules.start(players, seed, settings)
        val timer = Timer.initial(rules.timer(settings, start.stage), players, start.turns.currentPlayerId, now)
        val initial = stageState(rules, players, start, timer)
        val state = initial.copy(players = initial.players.map { it.copy(moveOptions = rules.moveOptions(initial, it.id)) })
        val gameSettings = linkedMapOf<String, Any?>(GAME_CODE to rules.code).apply { putAll(objectTree(settings)) }
        val meta = SessionMeta(GameMeta(rules.code, gameSettings), codec.toTree(rules.advancedRules(settings)))
        val document = linkedMapOf(STATUS to SessionStatus.IN_PROGRESS, META to meta, SessionEvents.STATE to stateTree(state))
        return listOf(tree(SessionEvents.put(SessionEvents.SESSION_STARTED, emptyList(), document)))
    }

    /** Ход игрока [playerId] по полному документу [document]; [seed] — seed партии (только на сервере). */
    fun move(
        rules: GameRules<Any, Any, Any, Any>,
        document: SessionDocument,
        playerId: UUID,
        move: Any,
        seed: Long,
        now: Instant,
    ): MoveOutcome {
        val session = document.session
        return when {
            session.status != SessionStatus.IN_PROGRESS -> MoveOutcome.NotInProgress
            Timer.isPaused(session.state.timer) -> MoveOutcome.Paused
            else -> when (val decision = rules.decide(session.state, settings(rules, session), seed, playerId, move)) {
                is Decision.Rejected -> MoveOutcome.Rejected(decision.code, decision.message)
                is Decision.Accepted -> MoveOutcome.Accepted(complete(rules, document, decision.events, now))
            }
        }
    }

    /** Время истекло: последствия решает игра. Пустой список — ничего не происходит. */
    fun timeout(rules: GameRules<Any, Any, Any, Any>, document: SessionDocument, seed: Long, now: Instant): List<SessionEvent> {
        val session = document.session
        if (session.status != SessionStatus.IN_PROGRESS || Timer.isPaused(session.state.timer)) return emptyList()
        val events = rules.onTimeout(session.state, settings(rules, session), seed)
        return if (events.isEmpty()) emptyList() else complete(rules, document, events, now)
    }

    /** Пауза — действие таймера (`game-paused`). */
    fun pause(document: SessionDocument, now: Instant): TimerOutcome = timerAction(document) { Timer.pause(it, now) }

    /** Продолжение (`game-resumed`): дедлайн сдвигается на длительность паузы. */
    fun resume(document: SessionDocument, now: Instant): TimerOutcome = timerAction(document) { Timer.resume(it, now) }

    /**
     * Отмена: варианты ходов всех игроков обнуляются (`move-options-changed`, как на конце партии), таймер (если есть)
     * останавливается, затем `session-cancelled`. Итога у отменённой партии нет.
     */
    fun cancel(document: SessionDocument, now: Instant): List<SessionEvent> {
        val state = document.session.state
        val timer = state.timer?.let { Timer.stop(it, state.turns.currentPlayerId, now) }
        val events = Players.changes(state) { emptyList() } + listOfNotNull(timer) + SessionEvents.cancelled()
        return events.map(::tree)
    }

    /** Событие для проекции [viewer]: закрытое для зрителя значение заменяется маской, но событие доставляется. */
    fun mask(rules: GameRules<Any, Any, Any, Any>, event: SessionEvent, viewer: ViewerKey): SessionEvent {
        if (viewer == ViewerKey.Full) return event
        return when (event.code) {
            SessionEvents.SESSION_STARTED -> event.copy(value = maskStart(rules, objectOf(event.value), viewer))
            Stages.STAGE_CHANGED -> event.copy(value = maskState(rules, objectOf(event.value), viewer))
            Players.MOVE_OPTIONS_CHANGED -> Players.mask(event, viewer)!!
            in PUBLIC_CODES -> event
            else -> tree(rules.mask(event, viewer))
        }
    }

    /** Типизированное чтение полного документа партии (проверка ходов, таймер, статус). */
    fun read(rules: GameRules<Any, Any, Any, Any>, document: Map<String, Any?>): SessionDocument {
        val state = objectOf(document[SessionEvents.STATE])
        val stage = state[Stages.STAGE] as? String ?: error("The session state has no stage")
        val session = Session(
            meta = codec.fromTree(document[META], SessionMeta::class.java),
            status = SessionStatus.valueOf(document[STATUS] as String),
            state = SessionState(
                stage = stage,
                players = (state[Players.PLAYERS] as List<*>).map { readPlayer(rules, stage, it) },
                timer = state[Timer.TIMER]?.let { codec.fromTree(it, TimerState::class.java) },
                turns = codec.fromTree(state[Turns.TURNS], TurnsState::class.java),
                result = state[Result.RESULT]?.let { codec.fromTree(it, ResultState::class.java) },
                game = readGame(rules, stage, state),
            ),
        )
        return SessionDocument(document, session)
    }

    /**
     * Дописывает к событиям игры события core: варианты ходов, часы — переключение при смене очереди или часы новой
     * стадии после перехода; конец партии — последним. Итог партии (`result-set`) допускается только вместе с концом
     * партии, переход стадии — один, последним событием игры и не вместе с концом.
     */
    private fun complete(
        rules: GameRules<Any, Any, Any, Any>,
        document: SessionDocument,
        gameEvents: List<SessionEvent>,
        now: Instant,
    ): List<SessionEvent> {
        val (ending, own) = gameEvents.map { expand(rules, document, it) }.partition { it.code == SessionEvents.SESSION_FINISHED }
        val finished = ending.isNotEmpty()
        val results = own.count { it.code == Result.RESULT_SET }
        check(results == 0 || finished) { "${Result.RESULT_SET} is allowed only together with ${SessionEvents.SESSION_FINISHED}" }
        check(results <= 1) { "${Result.RESULT_SET} must be set once, got $results" }
        val stageChanges = own.count { it.code == Stages.STAGE_CHANGED }
        check(stageChanges <= 1) { "${Stages.STAGE_CHANGED} must be at most once, got $stageChanges" }
        val stageChanged = stageChanges == 1
        check(!stageChanged || !finished) { "${Stages.STAGE_CHANGED} is not allowed together with ${SessionEvents.SESSION_FINISHED}" }
        check(!stageChanged || own.last().code == Stages.STAGE_CHANGED) { "${Stages.STAGE_CHANGED} must be the last game event" }

        val after = read(rules, fold(document.tree, own)!!).session
        val players = Players.changes(after.state) { if (finished) emptyList() else rules.moveOptions(after.state, it) }
        val previous = document.session.state.turns
        val stageTimer = rules.timer(settings(rules, document.session), after.state.stage)
        val timer = when {
            finished -> after.state.timer?.let { Timer.stop(it, previous.currentPlayerId, now) }
            stageChanged -> Timer.initial(stageTimer, after.state.players.map { it.id }, after.state.turns.currentPlayerId, now)?.let(Timer::started)
            else -> Timer.switch(after.state.timer, stageTimer, previous, after.state.turns, now)
        }
        return own + (players + listOfNotNull(timer) + ending.take(1)).map(::tree)
    }

    /**
     * Событие игры как дерево; переход стадии ([Stages.changed]) — в `PUT ["state"]` полного состояния новой стадии:
     * игроки те же, без вариантов ходов, часов и итога нет.
     */
    private fun expand(rules: GameRules<Any, Any, Any, Any>, document: SessionDocument, event: SessionEvent): SessionEvent {
        if (event.code != Stages.STAGE_CHANGED) return tree(event)
        @Suppress("UNCHECKED_CAST")
        val start = event.value as? GameStart<Any> ?: error("${Stages.STAGE_CHANGED} must carry a GameStart, use Stages.changed")
        val state = stageState(rules, document.session.state.players.map { it.id }, start, timer = null)
        return event.copy(value = stateTree(state))
    }

    /** Состояние стадии [start] с часами [timer]: варианты ходов пусты, итога нет. */
    private fun stageState(
        rules: GameRules<Any, Any, Any, Any>,
        players: List<UUID>,
        start: GameStart<Any>,
        timer: TimerState?,
    ): SessionState<Any> {
        checkStartPlayers(rules, start.stage, players, start.players)
        return SessionState(
            stage = start.stage,
            players = Players.initial(players, start.players),
            timer = timer,
            turns = start.turns,
            result = null,
            game = start.game,
        )
    }

    /** Advanced-настройки партии (GameRules.advancedClass) из `meta.game.settings` без кода игры. */
    private fun settings(rules: GameRules<Any, Any, Any, Any>, session: Session): Any =
        codec.fromTree(session.meta.game.settings - GAME_CODE, rules.advancedClass)

    private fun timerAction(document: SessionDocument, change: (TimerState) -> SessionEvent?): TimerOutcome {
        val session = document.session
        if (session.status != SessionStatus.IN_PROGRESS) return TimerOutcome.Unchanged
        val timer = session.state.timer ?: return TimerOutcome.NotPausable
        val event = change(timer) ?: return TimerOutcome.Unchanged
        return TimerOutcome.Changed(listOf(tree(event)))
    }

    /** Начальный документ для [viewer]: состояние — по маске [maskState]. */
    private fun maskStart(rules: GameRules<Any, Any, Any, Any>, document: Map<String, Any?>, viewer: ViewerKey): Map<String, Any?> =
        LinkedHashMap(document).apply { put(SessionEvents.STATE, maskState(rules, objectOf(document[SessionEvents.STATE]), viewer)) }

    /** Состояние стадии для [viewer]: чужие варианты ходов скрыты, игровая часть — по маске игры. */
    private fun maskState(rules: GameRules<Any, Any, Any, Any>, state: Map<String, Any?>, viewer: ViewerKey): Map<String, Any?> {
        val stage = state[Stages.STAGE] as String
        val players = (state[Players.PLAYERS] as List<*>).map { playerTree(Players.mask(readPlayer(rules, stage, it), viewer)) }
        val game = rules.maskGame(readGame(rules, stage, state), viewer)
        return linkedMapOf(
            Stages.STAGE to stage,
            Players.PLAYERS to players,
            Timer.TIMER to state[Timer.TIMER],
            Turns.TURNS to state[Turns.TURNS],
            Result.RESULT to state[Result.RESULT],
        ).apply { putAll(objectTree(game)) }
    }

    /** Состояние в документе: блоки core, рядом — поля игры. */
    private fun stateTree(state: SessionState<Any>): Map<String, Any?> {
        val game = objectTree(state.game)
        val clash = game.keys.filter { it in CORE_KEYS }
        check(clash.isEmpty()) { "Game state fields $clash clash with the core blocks $CORE_KEYS" }
        return linkedMapOf(
            Stages.STAGE to state.stage,
            Players.PLAYERS to state.players.map(::playerTree),
            Timer.TIMER to codec.toTree(state.timer),
            Turns.TURNS to codec.toTree(state.turns),
            Result.RESULT to codec.toTree(state.result),
        ).apply { putAll(game) }
    }

    /** Объект игрока в документе: поля core, рядом — поля игры. */
    private fun playerTree(player: PlayerState): Map<String, Any?> {
        val core = linkedMapOf<String, Any?>("id" to codec.toTree(player.id), "moveOptions" to codec.toTree(player.moveOptions))
        val game = player.game?.let(::objectTree).orEmpty()
        val clash = game.keys.filter { it in PlayerState.FIELDS }
        check(clash.isEmpty()) { "Game player fields $clash clash with the core player fields ${PlayerState.FIELDS}" }
        return core.apply { putAll(game) }
    }

    /** Игровая часть состояния стадии [stage] (GameRules.gameStateClass). */
    private fun readGame(rules: GameRules<Any, Any, Any, Any>, stage: String, state: Map<String, Any?>): Any {
        @Suppress("UNCHECKED_CAST")
        val type = rules.gameStateClass(stage) as Class<Any>
        return codec.fromTree(state - CORE_KEYS, type)
    }

    /** Игрок из документа: поля core и поля игры стадии [stage] (GameRules.playerClass). */
    private fun readPlayer(rules: GameRules<Any, Any, Any, Any>, stage: String, tree: Any?): PlayerState {
        val player = objectOf(tree)
        val core = codec.fromTree(player.filterKeys { it in PlayerState.FIELDS }, PlayerState::class.java)
        val fields = player - PlayerState.FIELDS
        val playerClass = rules.playerClass(stage)
        if (playerClass == null) {
            check(fields.isEmpty()) { "Game ${rules.code} has no player fields in stage $stage, got ${fields.keys}" }
            return core
        }
        return core.copy(game = codec.fromTree(fields, playerClass))
    }

    /** Поля игры у игроков стадии [stage] — у каждого игрока партии и класса GameRules.playerClass. */
    private fun checkStartPlayers(rules: GameRules<Any, Any, Any, Any>, stage: String, players: List<UUID>, fields: Map<UUID, Any>) {
        val playerClass = rules.playerClass(stage)
        if (playerClass == null) {
            check(fields.isEmpty()) { "Game ${rules.code} declares no player class in stage $stage, but sets player fields" }
            return
        }
        check(fields.keys == players.toSet()) { "Game ${rules.code} must set player fields for every player in stage $stage" }
        check(fields.values.all(playerClass::isInstance)) { "Player fields of game ${rules.code} in stage $stage must be ${playerClass.name}" }
    }

    private fun tree(event: SessionEvent): SessionEvent = event.copy(value = codec.toTree(event.value))

    private fun objectTree(value: Any): Map<String, Any?> = objectOf(codec.toTree(value))

    @Suppress("UNCHECKED_CAST")
    private fun objectOf(tree: Any?): Map<String, Any?> =
        tree as? Map<String, Any?> ?: error("Expected a JSON object, got ${tree?.let { it::class.qualifiedName }}")

    companion object {
        const val STATUS = "status"
        const val META = "meta"

        /** Поле кода игры в meta.game.settings (как в настройках комнаты). */
        const val GAME_CODE = "gameCode"

        /** Блоки core в состоянии; остальные поля состояния — игровые. */
        val CORE_KEYS = setOf(Stages.STAGE, Players.PLAYERS, Timer.TIMER, Turns.TURNS, Result.RESULT)

        /** Коды публичных событий платформы и core: маски нет. */
        private val PUBLIC_CODES = setOf(
            SessionEvents.SESSION_FINISHED,
            SessionEvents.SESSION_CANCELLED,
            Turns.TURN_CHANGED,
            Timer.TIMER_STARTED,
            Timer.CLOCK_SWITCHED,
            Timer.GAME_PAUSED,
            Timer.GAME_RESUMED,
            Timer.TIMER_STOPPED,
            Result.RESULT_SET,
        )

        /** Свёртка событий общим применителем; [document] null — журнал с начала. */
        fun fold(document: Map<String, Any?>?, events: List<SessionEvent>): Map<String, Any?>? = DocumentPatch.fold(document, events)
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
