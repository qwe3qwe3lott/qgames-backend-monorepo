package site.qgames.games.core

import java.util.UUID

/** Очерёдность: чей ход, текущий раунд и сколько их всего. */
data class TurnsState(
    /** Чей сейчас ход; null — ход ничей или партия закончилась. */
    val currentPlayerId: UUID?,
    /** Текущий раунд, с 1. */
    val round: Int,
    /** Сколько раундов в партии; null — число раундов не ограничено. */
    val rounds: Int?,
)

/**
 * Очерёдность (`state.turns`): ходы по кругу в порядке мест. Когда передать ход, решает игра: она вызывает [next] и
 * выдаёт [changed] — событие `turn-changed` (`PUT ["state", "turns"]`, блок целиком).
 */
object Turns {

    /** Сменился текущий игрок или раунд. */
    const val TURN_CHANGED = "turn-changed"

    /** Ключ блока в состоянии. */
    const val TURNS = "turns"

    /**
     * Очерёдность новой партии: первым ходит [first] (игрок партии), раунд — первый. Порядок дальнейших ходов — по
     * местам ([next]).
     */
    fun initial(players: List<UUID>, rounds: Int?, first: UUID): TurnsState {
        require(first in players) { "First player $first is not a player of the session" }
        return TurnsState(first, round = 1, rounds = rounds)
    }

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

    fun changed(turns: TurnsState): SessionEvent = SessionEvents.put(TURN_CHANGED, SessionEvents.path(SessionEvents.STATE, TURNS), turns)
}
