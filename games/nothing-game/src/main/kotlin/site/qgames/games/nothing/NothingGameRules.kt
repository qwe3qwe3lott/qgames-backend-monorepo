package site.qgames.games.nothing

import site.qgames.games.api.GameRules
import site.qgames.games.api.MoveResult
import site.qgames.games.api.SettingsViolation
import site.qgames.games.api.TimeControl
import site.qgames.games.api.TimeControlMode
import site.qgames.games.api.TimeControlRules
import java.time.Duration
import java.util.UUID

/**
 * «Игра в ничто»: игроки по очереди делают ход PASS, после [NothingSettings.rounds] кругов партия заканчивается
 * без победителя. Нужна, чтобы проверить движок целиком: очерёдность, журнал ходов, таймеры, конец партии.
 */
class NothingGameRules : GameRules<NothingState, NothingMove, NothingSettings> {

    override val code = CODE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val stateClass = NothingState::class.java
    override val moveClass = NothingMove::class.java
    override val settingsClass = NothingSettings::class.java
    override val stateSchemaVersion = 1

    override val timeControl = TimeControlRules(
        modes = setOf(TimeControlMode.NONE, TimeControlMode.PER_MOVE),
        moveSeconds = 5..600,
        default = TimeControl(TimeControlMode.NONE, moveSeconds = null),
    )

    override val reconnectGrace: Duration = Duration.ofSeconds(30)

    override val defaultSettings = NothingSettings(rounds = 3)

    override val settingsLimits = NothingSettingsLimits(rounds = ROUNDS)

    override fun validateSettings(settings: NothingSettings): List<SettingsViolation> =
        if (settings.rounds in ROUNDS) {
            emptyList()
        } else {
            listOf(SettingsViolation("rounds", "must be between ${ROUNDS.first} and ${ROUNDS.last}"))
        }

    override fun initialState(players: List<UUID>, seed: Long, settings: NothingSettings) =
        NothingState(players = players, turn = 0, rounds = settings.rounds)

    override fun applyMove(state: NothingState, player: UUID, move: NothingMove): MoveResult<NothingState> = when {
        isFinished(state) -> MoveResult.Rejected("GAME_FINISHED", "The game is already finished")
        move.type != PASS -> MoveResult.Rejected("UNKNOWN_MOVE", "Only $PASS is allowed")
        currentPlayer(state) != player -> MoveResult.Rejected("NOT_YOUR_TURN", "It is not your turn")
        else -> MoveResult.Applied(pass(state))
    }

    override fun isFinished(state: NothingState) = state.turn >= state.rounds * state.players.size

    override fun playerToMove(state: NothingState): UUID? = if (isFinished(state)) null else currentPlayer(state)

    /** Время вышло — ход пропускается, как будто игрок сделал PASS. */
    override fun onMoveTimeout(state: NothingState): NothingState = if (isFinished(state)) state else pass(state)

    override fun view(state: NothingState, viewer: UUID) = NothingView(
        players = state.players,
        round = minOf(state.turn / state.players.size + 1, state.rounds),
        rounds = state.rounds,
        currentPlayer = playerToMove(state),
        finished = isFinished(state),
    )

    private fun pass(state: NothingState) = state.copy(turn = state.turn + 1)

    private fun currentPlayer(state: NothingState) = state.players[state.turn % state.players.size]

    companion object {
        const val CODE = "nothing"
        const val PASS = "PASS"
        private val ROUNDS = 1..50
    }
}

/** Настройки партии. */
data class NothingSettings(
    val rounds: Int,
)

/** Границы настроек для формы. */
data class NothingSettingsLimits(
    val rounds: IntRange,
)

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
