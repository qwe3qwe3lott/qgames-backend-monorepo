package site.qgames.games.api.timer

import site.qgames.games.api.TimeControl
import site.qgames.games.api.TimeControlMode
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
 * Модуль timer. Режим [TimeControlMode.PER_MOVE]: старт и каждый ход (в том числе по истечении времени) ставят
 * дедлайн `now + moveSeconds`. Пауза запрещает ходы и замораживает дедлайн; продолжение сдвигает его на длительность
 * паузы. Конец и отмена партии останавливают таймер (STOPPED без дедлайна), чтобы финальное состояние было
 * самодостаточным для повтора. Все изменения — событие `timer-changed` с таймером целиком.
 */
object TimerModule {

    /** Таймер новой партии; null — режим без таймера. */
    fun initial(timeControl: TimeControl, now: Instant): TimerState? = restart(timeControl, now)

    /** Таймер следующего хода; null — режим без таймера. */
    fun restart(timeControl: TimeControl, now: Instant): TimerState? = when (timeControl.mode) {
        TimeControlMode.NONE -> null
        TimeControlMode.PER_MOVE -> TimerState(TimerStatus.RUNNING, now.plusSeconds(timeControl.moveSeconds!!.toLong()), pausedAt = null)
    }

    /** Таймер на паузе с момента [now]; null — уже на паузе. */
    fun pause(timer: TimerState, now: Instant): TimerState? =
        if (timer.status == TimerStatus.PAUSED) null else timer.copy(status = TimerStatus.PAUSED, pausedAt = now)

    /** Таймер после паузы: дедлайн сдвинут на её длительность; null — таймер не на паузе. */
    fun resume(timer: TimerState, now: Instant): TimerState? {
        if (timer.status != TimerStatus.PAUSED || timer.pausedAt == null) return null
        return TimerState(TimerStatus.RUNNING, timer.deadline?.plus(pauseLength(timer, now)), pausedAt = null)
    }

    /** Сколько длится пауза к моменту [now]; ноль — таймер идёт. */
    fun pauseLength(timer: TimerState, now: Instant): Duration =
        timer.pausedAt?.let { Duration.between(it, now).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO

    /** Остановленный таймер (конец или отмена партии); null — уже остановлен. */
    fun stop(timer: TimerState): TimerState? =
        if (timer.status == TimerStatus.STOPPED) null else TimerState(TimerStatus.STOPPED, deadline = null, pausedAt = null)

    fun isPaused(timer: TimerState?): Boolean = timer?.status == TimerStatus.PAUSED

    /** Дедлайн, по которому сервис ищет истёкшее время хода: только у идущего таймера. */
    fun runningDeadline(timer: TimerState?): Instant? = timer?.takeIf { it.status == TimerStatus.RUNNING }?.deadline
}
