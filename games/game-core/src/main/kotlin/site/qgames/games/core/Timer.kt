package site.qgames.games.core

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Часы игроков: у каждого игрока запас [baseSeconds] на всю стадию и добавочное время [bonusSeconds] вида [bonusType]
 * (0 — без него). Таймеры ведёт сервис game, игра объявляет только границы ([TimeControlRules]). Партия без таймера —
 * `timeControl = null`.
 */
data class TimeControl(
    val baseSeconds: Int,
    val bonusType: TimeBonusType,
    val bonusSeconds: Int,
)

/** Вид добавочного времени часов. */
enum class TimeBonusType {
    /** Инкремент (Фишер): после своего хода игрок получает к запасу bonusSeconds. */
    INCREMENT,

    /**
     * Задержка (простая): в начале хода сначала тратится bonusSeconds, затем запас; неистраченная задержка не
     * копится.
     */
    DELAY,
}

/**
 * Часы в advanced-настройках игры: включены ли ([enabled]) и их значения. Значения заполнены и у выключенных часов:
 * форма показывает их при включении.
 */
data class TimeControlSettings(
    val enabled: Boolean,
    val baseSeconds: Int,
    val bonusType: TimeBonusType,
    val bonusSeconds: Int,
) {
    /** Часы партии; null — без таймера. */
    fun toTimeControl(): TimeControl? = if (enabled) TimeControl(baseSeconds, bonusType, bonusSeconds) else null
}

/**
 * Что игра разрешает: можно ли играть без таймера ([optional]) и границы запаса и добавочного времени (в секундах).
 * Оба вида добавочного времени разрешены всем играм.
 */
data class TimeControlRules(
    val optional: Boolean,
    val baseSeconds: IntRange,
    val bonusSeconds: IntRange,
) {
    /**
     * Нарушения в часах [settings]; поля — внутри часов (`enabled`, `baseSeconds`). Значения выключенных часов не
     * проверяются.
     */
    fun validate(settings: TimeControlSettings): List<SettingsViolation> = buildList {
        if (!settings.enabled) {
            if (!optional) add(SettingsViolation("enabled", "must be true: the game is played only with a clock"))
            return@buildList
        }
        if (settings.baseSeconds !in baseSeconds) {
            add(SettingsViolation("baseSeconds", "must be between ${baseSeconds.first} and ${baseSeconds.last}"))
        }
        if (settings.bonusSeconds !in bonusSeconds) {
            add(SettingsViolation("bonusSeconds", "must be between ${bonusSeconds.first} and ${bonusSeconds.last}"))
        }
    }
}

/**
 * Часы стадии партии ([GameRules.timer]): часы игроков ([Players]) или общий отсчёт на всю стадию ([Stage]) — для
 * стадий, где ходят сразу несколько игроков.
 */
sealed interface StageTimer {

    data class Players(val timeControl: TimeControl) : StageTimer

    /** Общий отсчёт [seconds] секунд с начала стадии; по истечении — GameRules.onTimeout. */
    data class Stage(val seconds: Int) : StageTimer
}

/** Вид часов ([TimerState.kind]). */
enum class TimerKind {
    /** Часы игроков: запас у каждого ([TimerState.clocks]), дедлайн — у ходящего. */
    PLAYERS,

    /** Общий отсчёт стадии: один дедлайн на всех, запасов нет (`clocks` пуст). */
    STAGE,
}

/** Часы партии. Пауза — часть таймера: у партии без таймера пауз нет. */
data class TimerState(
    val kind: TimerKind,
    val status: TimerStatus,
    /**
     * Когда кончится время: у часов игроков — время того, чей ход (начало хода + его запас + задержка, если она есть), у
     * отсчёта стадии — время стадии. null — ход ничей или таймер остановлен. На паузе не меняется: остаток —
     * deadline − pausedAt.
     */
    val deadline: Instant?,
    /** С какого момента таймер на паузе; null — идёт. */
    val pausedAt: Instant?,
    /**
     * Запас каждого игрока на момент последней смены хода. У ходящего текущий запас — min(remainingMs, deadline − now),
     * сверх него — остаток задержки. У отсчёта стадии пуст.
     */
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
 * Часы стадии (`state.timer`, [StageTimer]).
 *
 * Часы игроков ([TimerKind.PLAYERS], [TimeControl]). Старт: у всех запас `baseSeconds`, дедлайн ходящего — `now + запас`
 * (+ задержка). Часы переключаются только при смене очереди — изменении `state.turns` (игрок или раунд, в том числе ход
 * снова у того же игрока, как в одиночной партии): у ходившего остаётся `min(запас, остаток)` (+ инкремент), следующему
 * ставится дедлайн `now + его запас` (+ задержка). Ход без смены очереди часы не трогает. Время вышло —
 * последствия решает игра (`GameRules.onTimeout`); запас ходившего тогда 0 (плюс инкремент, если очередь сменилась).
 *
 * Отсчёт стадии ([TimerKind.STAGE]) — один дедлайн `now + seconds` с начала стадии, без запасов; ходы его не
 * переключают.
 *
 * Пауза запрещает ходы и замораживает дедлайн; продолжение сдвигает его ровно на длительность паузы по меткам времени,
 * поэтому сколько угодно пауз не добавляют времени (задержка заново не начисляется). Конец и отмена партии останавливают часы
 * (STOPPED без дедлайна, у ходившего — его остаток), чтобы финальное состояние было самодостаточным для повтора.
 *
 * События: `timer-started` (часы новой стадии), `clock-switched` и `timer-stopped` — таймер целиком (PUT), `game-paused`
 * и `game-resumed` — изменившиеся поля (PATCH).
 */
object Timer {

    const val TIMER_STARTED = "timer-started"
    const val CLOCK_SWITCHED = "clock-switched"
    const val GAME_PAUSED = "game-paused"
    const val GAME_RESUMED = "game-resumed"
    const val TIMER_STOPPED = "timer-stopped"

    /** Ключ блока в состоянии. */
    const val TIMER = "timer"

    private val PATH = SessionEvents.path(SessionEvents.STATE, TIMER)

    /**
     * Часы новой стадии с момента [now]: у часов игроков — полный запас у [players], идут у [current]; у отсчёта
     * стадии — общий дедлайн. null — стадия без таймера.
     */
    fun initial(stageTimer: StageTimer?, players: List<UUID>, current: UUID?, now: Instant): TimerState? = when (stageTimer) {
        null -> null
        is StageTimer.Stage ->
            TimerState(TimerKind.STAGE, TimerStatus.RUNNING, now.plusSeconds(stageTimer.seconds.toLong()), pausedAt = null, emptyList())
        is StageTimer.Players -> {
            val timeControl = stageTimer.timeControl
            val base = timeControl.baseSeconds * MILLIS
            val deadline = current?.let { now.plusMillis(base + delayMs(timeControl)) }
            TimerState(TimerKind.PLAYERS, TimerStatus.RUNNING, deadline, pausedAt = null, players.map { PlayerClock(it, base) })
        }
    }

    /** Часы новой стадии (`timer-started`, следом за `stage-changed`). */
    fun started(timer: TimerState): SessionEvent = SessionEvents.put(TIMER_STARTED, PATH, timer)

    /**
     * Смена очереди с [previous] на [next] (`clock-switched`): у ходившего остаётся его запас к [now] плюс инкремент,
     * следующему (может быть он же) — дедлайн по его запасу и задержке. null — таймера нет, это отсчёт стадии или
     * очередь не сменилась.
     */
    fun switch(timer: TimerState?, stageTimer: StageTimer?, previous: TurnsState, next: TurnsState, now: Instant): SessionEvent? {
        if (timer == null || timer.kind != TimerKind.PLAYERS || stageTimer !is StageTimer.Players || previous == next) return null
        val timeControl = stageTimer.timeControl
        val clocks = timer.clocks.map {
            if (it.playerId == previous.currentPlayerId) it.copy(remainingMs = remaining(timer, it, now) + incrementMs(timeControl)) else it
        }
        val deadline = next.currentPlayerId?.let { id ->
            now.plusMillis(clocks.single { it.playerId == id }.remainingMs + delayMs(timeControl))
        }
        return SessionEvents.put(CLOCK_SWITCHED, PATH, TimerState(TimerKind.PLAYERS, TimerStatus.RUNNING, deadline, pausedAt = null, clocks))
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
     * Остановка часов (`timer-stopped`, конец или отмена партии): у [current] — его запас к [now]; null — уже
     * остановлены.
     */
    fun stop(timer: TimerState, current: UUID?, now: Instant): SessionEvent? {
        if (timer.status == TimerStatus.STOPPED) return null
        val clocks = timer.clocks.map { if (it.playerId == current) it.copy(remainingMs = remaining(timer, it, now)) else it }
        return SessionEvents.put(TIMER_STOPPED, PATH, TimerState(timer.kind, TimerStatus.STOPPED, deadline = null, pausedAt = null, clocks))
    }

    fun isPaused(timer: TimerState?): Boolean = timer?.status == TimerStatus.PAUSED

    /** Дедлайн, по которому сервис ищет истёкшее время: только у идущего таймера (часов игроков или отсчёта стадии). */
    fun runningDeadline(timer: TimerState?): Instant? = timer?.takeIf { it.status == TimerStatus.RUNNING }?.deadline

    /**
     * Запас [clock] ходящего к [now] (на паузе — к её началу): не больше сохранённого (при задержке она тратится
     * первой) и не меньше нуля; без дедлайна — сохранённый запас.
     */
    private fun remaining(timer: TimerState, clock: PlayerClock, now: Instant): Long {
        val deadline = timer.deadline ?: return clock.remainingMs
        val at = timer.pausedAt ?: now
        return Duration.between(at, deadline).toMillis().coerceIn(0, clock.remainingMs)
    }

    private fun delayMs(timeControl: TimeControl): Long =
        if (timeControl.bonusType == TimeBonusType.DELAY) timeControl.bonusSeconds * MILLIS else 0

    private fun incrementMs(timeControl: TimeControl): Long =
        if (timeControl.bonusType == TimeBonusType.INCREMENT) timeControl.bonusSeconds * MILLIS else 0

    private const val MILLIS = 1000L
}
