package site.qgames.games.api

/**
 * Шахматные часы партии — единственный вид таймера: у каждого игрока запас [baseSeconds] на всю партию, после своего
 * хода он получает добавку [incrementSeconds] (Фишер; 0 — без добавки). Таймеры ведёт сервис game, игра объявляет
 * только границы ([TimeControlRules]). Партия без таймера — `timeControl = null`.
 */
data class TimeControl(
    val baseSeconds: Int,
    val incrementSeconds: Int,
)

/**
 * Что игра разрешает: можно ли играть без таймера ([optional]), границы запаса и добавки (в секундах) и правила времени
 * по умолчанию ([default]; null — без таймера, только при [optional]).
 */
data class TimeControlRules(
    val optional: Boolean,
    val baseSeconds: IntRange,
    val incrementSeconds: IntRange,
    val default: TimeControl?,
)
