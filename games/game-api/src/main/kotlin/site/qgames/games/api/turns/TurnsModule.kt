package site.qgames.games.api.turns

import java.util.UUID

/** Очерёдность (модуль turns): чей ход, текущий раунд и сколько их всего. */
data class TurnsState(
    /** Чей сейчас ход; null — ход ничей или партия закончилась. */
    val currentPlayerId: UUID?,
    /** Текущий раунд, с 1. */
    val round: Int,
    /** Сколько раундов в партии; null — число раундов не ограничено. */
    val rounds: Int?,
)

/**
 * Модуль turns: ходы по кругу в порядке мест. Когда передать ход, решает игра: она вызывает [next] и выдаёт событие
 * `turns-changed` (блок целиком).
 */
object TurnsModule {

    /** Первый ход — у первого игрока. */
    fun initial(players: List<UUID>, rounds: Int?) = TurnsState(players.first(), round = 1, rounds = rounds)

    /**
     * Ход переходит к следующему по месту; после последнего начинается новый раунд. Если раунды кончились, ход ничей
     * (`currentPlayerId = null`), а раунд остаётся последним.
     */
    fun next(turns: TurnsState, players: List<UUID>): TurnsState {
        val current = turns.currentPlayerId ?: return turns
        val index = players.indexOf(current)
        if (index < players.lastIndex) {
            return turns.copy(currentPlayerId = players[index + 1])
        }
        val rounds = turns.rounds
        return if (rounds != null && turns.round >= rounds) {
            turns.copy(currentPlayerId = null)
        } else {
            turns.copy(currentPlayerId = players.first(), round = turns.round + 1)
        }
    }
}
