package site.qgames.games.api.timer

import site.qgames.games.api.TimeControl
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionEvents
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Шахматные часы партии (модуль timer). Пауза — часть таймера: у партии без таймера пауз нет. */
data class TimerState(
    val status: TimerStatus,
    /**
     * Когда кончится запас того, чей ход; null — ход ничей или таймер остановлен. На паузе не меняется: остаток —
     * deadline − pausedAt.
     */
    val deadline: Instant?,
    /** С какого момента таймер на паузе; null — идёт. */
    val pausedAt: Instant?,
    /** Запас каждого игрока на момент последней смены хода; у ходящего текущий остаток — deadline − now. */
    val clocks: List<PlayerClock>,
)

/** Запас времени игрока в миллисекундах. */
data class PlayerClock(
    val playerId: UUID,
    val remainingMs: Long,
)

enum class TimerStatus {
    RUNNING,
    PAUSED,

    /** Партия закончилась или отменена: дедлайна и паузы нет, часы показывают итоговые остатки. */
    STOPPED,
}

/**
 * Модуль timer (`state.timer`) — шахматные часы ([TimeControl]). Старт: у всех запас `baseSeconds`, дедлайн ходящего —
 * `now + запас`. Часы переключаются только при смене очереди: ходивший получает остаток и добавку `incrementSeconds`,
 * следующему ставится дедлайн по его запасу. Ход без смены очереди часы не трогает. Время вышло — последствия решает
 * игра (`GameRules.onTimeout`); запас ходившего тогда 0 (плюс добавка, если очередь сменилась). Пауза запрещает ходы и
 * замораживает дедлайн; продолжение сдвигает его на длительность паузы. Конец и отмена партии останавливают часы
 * (STOPPED без дедлайна, у ходившего — его остаток), чтобы финальное состояние было самодостаточным для повтора.
 *
 * События: `clock-switched` и `timer-stopped` — таймер целиком (PUT), `game-paused` и `game-resumed` — изменившиеся
 * поля (PATCH).
 */
object TimerModule {

    const val CLOCK_SWITCHED = "clock-switched"
    const val GAME_PAUSED = "game-paused"
    const val GAME_RESUMED = "game-resumed"
    const val TIMER_STOPPED = "timer-stopped"

    /** Ключ блока в состоянии. */
    const val TIMER = "timer"

    private val PATH = SessionEvents.path(SessionEvents.STATE, TIMER)

    /** Часы новой партии: у [players] полный запас, идут у [current]; null — партия без таймера. */
    fun initial(timeControl: TimeControl?, players: List<UUID>, current: UUID?, now: Instant): TimerState? {
        if (timeControl == null) return null
        val base = timeControl.baseSeconds * MILLIS
        return TimerState(TimerStatus.RUNNING, current?.let { now.plusMillis(base) }, pausedAt = null, players.map { PlayerClock(it, base) })
    }

    /**
     * Смена очереди с [previous] на [next] (`clock-switched`): ходивший получает остаток и добавку, следующему —
     * дедлайн по его запасу. null — таймера нет или очередь не сменилась.
     */
    fun switch(timer: TimerState?, timeControl: TimeControl?, previous: UUID?, next: UUID?, now: Instant): SessionEvent? {
        if (timer == null || timeControl == null || previous == next) return null
        val clocks = timer.clocks.map {
            if (it.playerId == previous) it.copy(remainingMs = remaining(timer, it, now) + timeControl.incrementSeconds * MILLIS) else it
        }
        val deadline = next?.let { id -> now.plusMillis(clocks.single { it.playerId == id }.remainingMs) }
        return SessionEvents.put(CLOCK_SWITCHED, PATH, TimerState(TimerStatus.RUNNING, deadline, pausedAt = null, clocks))
    }

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

    /**
     * Остановка часов (`timer-stopped`, конец или отмена партии): у [current] — его остаток к [now] без добавки; null —
     * уже остановлены.
     */
    fun stop(timer: TimerState, current: UUID?, now: Instant): SessionEvent? {
        if (timer.status == TimerStatus.STOPPED) return null
        val clocks = timer.clocks.map { if (it.playerId == current) it.copy(remainingMs = remaining(timer, it, now)) else it }
        return SessionEvents.put(TIMER_STOPPED, PATH, TimerState(TimerStatus.STOPPED, deadline = null, pausedAt = null, clocks))
    }

    fun isPaused(timer: TimerState?): Boolean = timer?.status == TimerStatus.PAUSED

    /** Дедлайн, по которому сервис ищет истёкшее время хода: только у идущего таймера. */
    fun runningDeadline(timer: TimerState?): Instant? = timer?.takeIf { it.status == TimerStatus.RUNNING }?.deadline

    /** Остаток [clock] ходящего к [now] (на паузе — к её началу), не меньше нуля; без дедлайна — сохранённый запас. */
    private fun remaining(timer: TimerState, clock: PlayerClock, now: Instant): Long {
        val deadline = timer.deadline ?: return clock.remainingMs
        val at = timer.pausedAt ?: now
        return Duration.between(at, deadline).toMillis().coerceAtLeast(0)
    }

    private const val MILLIS = 1000L
}
