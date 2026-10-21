package site.qgames.games.api.session

import site.qgames.games.api.Decision
import site.qgames.games.api.GameRules
import site.qgames.games.api.TimeControl
import site.qgames.games.api.core.CoreModule
import site.qgames.games.api.core.PlayerState
import site.qgames.games.api.timer.TimerModule
import site.qgames.games.api.timer.TimerState
import site.qgames.games.api.turns.TurnsState
import java.time.Instant
import java.util.UUID

/**
 * Платформа партии: собирает игру с модулями core, turns и timer. Чистые функции, без IO: сервис game отвечает за
 * журнал, блокировки и рассылку.
 *
 * - [start] — первое событие журнала `started`;
 * - [move], [timeout], [pause], [resume], [cancel] — события изменения по полному состоянию;
 * - [evolve] — один редьюсер и для полного состояния (сырые события), и для проекций (маскированные события):
 *   снапшот зрителя — свёртка его маскированных событий;
 * - [mask] — данные события для проекции зрителя.
 *
 * После событий игры платформа сама добавляет `player-changed` игрокам, у которых изменились варианты ходов, и
 * `timer-changed` с дедлайном следующего хода. Перед концом партии (`session-finished` игры, отмена) таймер
 * останавливается (`timer-changed` STOPPED), а событие конца уходит последним.
 */
object SessionEngine {

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
        val meta = SessionMeta(GameMeta(rules.code, SessionRules(timeControl, settings)))
        return listOf(SessionEvent(SessionEvents.STARTED, SessionStarted(meta, state)))
    }

    /** Ход игрока [playerId] по полному состоянию [session]. */
    fun move(rules: GameRules<Any, Any, Any>, session: Session, playerId: UUID, move: Any, now: Instant): MoveOutcome = when {
        session.status != SessionStatus.IN_PROGRESS -> MoveOutcome.NotInProgress
        TimerModule.isPaused(session.state.timer) -> MoveOutcome.Paused
        else -> when (val decision = rules.decide(session.state, playerId, move)) {
            is Decision.Rejected -> MoveOutcome.Rejected(decision.code, decision.message)
            is Decision.Accepted -> MoveOutcome.Accepted(complete(rules, session, decision.events, now))
        }
    }

    /** Время хода истекло: последствия решает игра. Пустой список — ничего не происходит. */
    fun timeout(rules: GameRules<Any, Any, Any>, session: Session, now: Instant): List<SessionEvent> {
        if (session.status != SessionStatus.IN_PROGRESS || TimerModule.isPaused(session.state.timer)) return emptyList()
        val events = rules.onTimeout(session.state)
        return if (events.isEmpty()) emptyList() else complete(rules, session, events, now)
    }

    /** Пауза — действие таймера. */
    fun pause(session: Session, now: Instant): TimerOutcome = timerAction(session) { TimerModule.pause(it, now) }

    /** Продолжение: дедлайн хода сдвигается на длительность паузы. */
    fun resume(session: Session, now: Instant): TimerOutcome = timerAction(session) { TimerModule.resume(it, now) }

    /**
     * Отмена: варианты ходов всех игроков обнуляются (`player-changed`, как на конце партии), таймер (если есть)
     * останавливается, затем `session-cancelled`.
     */
    fun cancel(session: Session): List<SessionEvent> =
        CoreModule.changes(session.state) { emptyList() } + stopTimer(session) + SessionEvents.cancelled()

    /** Применяет событие. [session] null — только для первого события `started`. */
    fun evolve(rules: GameRules<Any, Any, Any>, session: Session?, event: SessionEvent): Session {
        if (event.type == SessionEvents.STARTED) {
            val started = event.data as SessionStarted
            return Session(started.meta, SessionStatus.IN_PROGRESS, started.state)
        }
        checkNotNull(session) { "The first event of a session must be ${SessionEvents.STARTED}, got ${event.type}" }
        val state = session.state
        return when (event.type) {
            SessionEvents.FINISHED -> session.copy(status = SessionStatus.FINISHED)
            SessionEvents.CANCELLED -> session.copy(status = SessionStatus.CANCELLED)
            SessionEvents.PLAYER_CHANGED -> session.copy(state = CoreModule.evolve(state, event.data as PlayerState))
            SessionEvents.TURNS_CHANGED -> session.copy(state = state.copy(turns = event.data as TurnsState))
            SessionEvents.TIMER_CHANGED -> session.copy(state = state.copy(timer = event.data as TimerState))
            else -> session.copy(state = rules.evolve(state, event))
        }
    }

    fun fold(rules: GameRules<Any, Any, Any>, session: Session?, events: List<SessionEvent>): Session? =
        events.fold(session) { current, event -> evolve(rules, current, event) }

    /** Событие для проекции [viewer]: закрытое для зрителя заменяется маской, но событие доставляется. */
    fun mask(rules: GameRules<Any, Any, Any>, event: SessionEvent, viewer: ViewerKey): SessionEvent {
        if (viewer == ViewerKey.Full) return event
        return when (event.type) {
            SessionEvents.STARTED -> {
                val started = event.data as SessionStarted
                val state = started.state
                val masked = state.copy(players = state.players.map { CoreModule.mask(it, viewer) }, game = rules.maskGame(state.game, viewer))
                event.copy(data = started.copy(state = masked))
            }
            SessionEvents.PLAYER_CHANGED -> event.copy(data = CoreModule.mask(event.data as PlayerState, viewer))
            in SessionEvents.dataClasses -> event
            else -> rules.mask(event, viewer)
        }
    }

    /** Дописывает к событиям игры события модулей: варианты ходов, таймер следующего хода; конец партии — последним. */
    private fun complete(rules: GameRules<Any, Any, Any>, session: Session, gameEvents: List<SessionEvent>, now: Instant): List<SessionEvent> {
        val (ending, own) = gameEvents.partition { it.type == SessionEvents.FINISHED }
        val finished = ending.isNotEmpty()
        val afterGame = fold(rules, session, own)!!
        val players = CoreModule.changes(afterGame.state) { if (finished) emptyList() else rules.moveOptions(afterGame.state, it) }
        val timer = if (finished) afterGame.state.timer?.let(TimerModule::stop) else TimerModule.restart(session.meta.game.rules.timeControl, now)
        return buildList {
            addAll(own)
            addAll(players)
            timer?.let { add(SessionEvent(SessionEvents.TIMER_CHANGED, it)) }
            addAll(ending.take(1))
        }
    }

    private fun stopTimer(session: Session): List<SessionEvent> =
        listOfNotNull(session.state.timer?.let(TimerModule::stop)?.let { SessionEvent(SessionEvents.TIMER_CHANGED, it) })

    private fun timerAction(session: Session, change: (TimerState) -> TimerState?): TimerOutcome {
        if (session.status != SessionStatus.IN_PROGRESS) return TimerOutcome.Unchanged
        val timer = session.state.timer ?: return TimerOutcome.NotPausable
        val changed = change(timer) ?: return TimerOutcome.Unchanged
        return TimerOutcome.Changed(listOf(SessionEvent(SessionEvents.TIMER_CHANGED, changed)))
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
