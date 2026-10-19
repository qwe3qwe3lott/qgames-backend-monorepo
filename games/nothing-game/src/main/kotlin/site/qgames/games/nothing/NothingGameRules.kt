package site.qgames.games.nothing

import site.qgames.games.api.GameRules
import site.qgames.games.api.MoveResult
import java.util.UUID

/**
 * «Игра в ничто»: игроки по очереди делают ход PASS, после [NothingState.rounds] кругов партия заканчивается
 * без победителя. Нужна, чтобы проверить движок целиком: очерёдность, журнал ходов, конец партии.
 */
class NothingGameRules : GameRules<NothingState, NothingMove> {

    override val type = TYPE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val stateClass = NothingState::class.java
    override val moveClass = NothingMove::class.java
    override val stateSchemaVersion = 1

    override fun initialState(players: List<UUID>, seed: Long) = NothingState(players = players, turn = 0, rounds = ROUNDS)

    override fun applyMove(state: NothingState, player: UUID, move: NothingMove): MoveResult<NothingState> = when {
        isFinished(state) -> MoveResult.Rejected("GAME_FINISHED", "The game is already finished")
        move.type != PASS -> MoveResult.Rejected("UNKNOWN_MOVE", "Only $PASS is allowed")
        currentPlayer(state) != player -> MoveResult.Rejected("NOT_YOUR_TURN", "It is not your turn")
        else -> MoveResult.Applied(state.copy(turn = state.turn + 1))
    }

    override fun isFinished(state: NothingState) = state.turn >= state.rounds * state.players.size

    override fun view(state: NothingState, viewer: UUID) = NothingView(
        players = state.players,
        round = minOf(state.turn / state.players.size + 1, state.rounds),
        rounds = state.rounds,
        currentPlayer = if (isFinished(state)) null else currentPlayer(state),
        finished = isFinished(state),
    )

    private fun currentPlayer(state: NothingState) = state.players[state.turn % state.players.size]

    companion object {
        const val TYPE = "NOTHING"
        const val PASS = "PASS"
        private const val ROUNDS = 3
    }
}

data class NothingState(
    val players: List<UUID>,
    /** Сколько ходов уже сделано. */
    val turn: Int,
    val rounds: Int,
)

data class NothingMove(
    val type: String,
)

data class NothingView(
    val players: List<UUID>,
    val round: Int,
    val rounds: Int,
    val currentPlayer: UUID?,
    val finished: Boolean,
)
