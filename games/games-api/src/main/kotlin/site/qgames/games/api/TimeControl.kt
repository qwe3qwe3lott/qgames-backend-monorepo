package site.qgames.games.api

/**
 * Режим контроля времени партии. Таймеры ведёт сервис game, игра только объявляет, какие режимы поддерживает.
 * Расширяется новыми режимами (время на раунд, шахматные часы) без изменения уже поддерживаемых.
 */
enum class TimeControlMode {
    /** Без таймера. */
    NONE,

    /** Фиксированное время на каждый ход. */
    PER_MOVE,
}

/** Правила времени партии: режим и время на ход в секундах (только для [TimeControlMode.PER_MOVE]). */
data class TimeControl(
    val mode: TimeControlMode,
    val moveSeconds: Int?,
)

/** Что игра разрешает: режимы, границы времени на ход (в секундах) и правила времени по умолчанию. */
data class TimeControlRules(
    val modes: Set<TimeControlMode>,
    val moveSeconds: IntRange,
    val default: TimeControl,
)
