package site.qgames.games.api.timer

import site.qgames.games.api.TimeControl
import site.qgames.games.api.TimeControlMode
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionEvents
import java.time.Duration
import java.time.Instant

/** Таймер хода (модуль timer). Пауза — часть таймера: у партии без таймера пауз нет. */
data class TimerState(
    val status: TimerStatus,
    /** Когда истекает время текущего хода; null — ход ничей. На паузе не меняется: остаток — deadline − pausedAt. */
    val deadline: Instant?,
    /** С какого момента таймер на паузе; null — идёт. */
    val pausedAt: Instant?,
)

enum class TimerStatus {
    RUNNING,
    PAUSED,

    /** Партия закончилась или отменена: дедлайна и паузы нет. */
    STOPPED,
}

/**
 * Модуль timer (`state.timer`). Режим [TimeControlMode.PER_MOVE]: старт и каждый ход (в том числе по истечении времени)
 * ставят дедлайн `now + moveSeconds`. Пауза запрещает ходы и замораживает дедлайн; продолжение сдвигает его на
 * длительность паузы. Конец и отмена партии останавливают таймер (STOPPED без дедлайна), чтобы финальное состояние было
 * самодостаточным для повтора.
 *
 * События: `timer-started` и `timer-stopped` — таймер целиком (PUT), `game-paused` и `game-resumed` — изменившиеся поля
 * (PATCH).
 */
object TimerModule {

    const val TIMER_STARTED = "timer-started"
    const val GAME_PAUSED = "game-paused"
    const val GAME_RESUMED = "game-resumed"
    const val TIMER_STOPPED = "timer-stopped"

    /** Ключ блока в состоянии. */
    const val TIMER = "timer"

    private val PATH = SessionEvents.path(SessionEvents.STATE, TIMER)

    /** Таймер новой партии; null — режим без таймера. */
    fun initial(timeControl: TimeControl, now: Instant): TimerState? = running(timeControl, now)

    /** Таймер следующего хода (`timer-started`); null — режим без таймера. */
    fun restart(timeControl: TimeControl, now: Instant): SessionEvent? =
        running(timeControl, now)?.let { SessionEvents.put(TIMER_STARTED, PATH, it) }

    /** Пауза с момента [now] (`game-paused`); null — уже на паузе. */
    fun pause(timer: TimerState, now: Instant): SessionEvent? =
        if (timer.status == TimerStatus.PAUSED) {
            null
        } else {
            SessionEvents.patch(GAME_PAUSED, PATH, mapOf("status" to TimerStatus.PAUSED, "pausedAt" to now))
        }

    /** Продолжение после паузы (`game-resumed`): дедлайн сдвинут на её длительность; null — таймер не на паузе. */
    fun resume(timer: TimerState, now: Instant): SessionEvent? {
        if (timer.status != TimerStatus.PAUSED || timer.pausedAt == null) return null
        val deadline = timer.deadline?.plus(pauseLength(timer, now))
        return SessionEvents.patch(GAME_RESUMED, PATH, mapOf("status" to TimerStatus.RUNNING, "deadline" to deadline, "pausedAt" to null))
    }

    /** Сколько длится пауза к моменту [now]; ноль — таймер идёт. */
    fun pauseLength(timer: TimerState, now: Instant): Duration =
        timer.pausedAt?.let { Duration.between(it, now).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO

    /** Остановка таймера (`timer-stopped`, конец или отмена партии); null — уже остановлен. */
    fun stop(timer: TimerState): SessionEvent? =
        if (timer.status == TimerStatus.STOPPED) {
            null
        } else {
            SessionEvents.put(TIMER_STOPPED, PATH, TimerState(TimerStatus.STOPPED, deadline = null, pausedAt = null))
        }

    fun isPaused(timer: TimerState?): Boolean = timer?.status == TimerStatus.PAUSED

    /** Дедлайн, по которому сервис ищет истёкшее время хода: только у идущего таймера. */
    fun runningDeadline(timer: TimerState?): Instant? = timer?.takeIf { it.status == TimerStatus.RUNNING }?.deadline

    private fun running(timeControl: TimeControl, now: Instant): TimerState? = when (timeControl.mode) {
        TimeControlMode.NONE -> null
        TimeControlMode.PER_MOVE -> TimerState(TimerStatus.RUNNING, now.plusSeconds(timeControl.moveSeconds!!.toLong()), pausedAt = null)
    }
}
