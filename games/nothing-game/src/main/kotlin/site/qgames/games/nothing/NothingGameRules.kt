package site.qgames.games.nothing

import site.qgames.games.api.Decision
import site.qgames.games.api.GameRules
import site.qgames.games.api.GameStart
import site.qgames.games.api.SettingsViolation
import site.qgames.games.api.TimeControl
import site.qgames.games.api.TimeControlMode
import site.qgames.games.api.TimeControlRules
import site.qgames.games.api.core.MoveOption
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionEvents
import site.qgames.games.api.session.SessionState
import site.qgames.games.api.turns.TurnsModule
import java.time.Duration
import java.util.UUID

/**
 * «Игра в ничто»: игроки по очереди делают ход PASS, после [NothingSettings.rounds] кругов партия заканчивается
 * без победителя. Нужна, чтобы проверить движок целиком: очерёдность, журнал, таймеры, конец партии.
 *
 * Модули: core (варианты ходов — PASS у того, чей ход), turns (ход по кругу, раунды из настроек), timer.
 * Собственное событие одно — `moved {playerId, type, timeout}`; скрытой информации нет, маски не нужны.
 * Истечение времени — автоматический PASS игрока, чьё время вышло (`moved` с `timeout = true`).
 */
class NothingGameRules : GameRules<NothingGameState, NothingMove, NothingSettings> {

    override val code = CODE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val gameStateClass = NothingGameState::class.java
    override val moveClass = NothingMove::class.java
    override val settingsClass = NothingSettings::class.java
    override val stateSchemaVersion = 2

    override val eventClasses: Map<String, Class<*>> = mapOf(MOVED to NothingMoved::class.java)

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

    override fun start(players: List<UUID>, seed: Long, settings: NothingSettings) =
        GameStart(NothingGameState(moves = 0), TurnsModule.initial(players, settings.rounds))

    override fun decide(state: SessionState<NothingGameState>, playerId: UUID, move: NothingMove): Decision {
        val current = state.turns.currentPlayerId
        return when {
            current == null -> Decision.Rejected("GAME_FINISHED", "The game is already finished")
            move.type != PASS -> Decision.Rejected("UNKNOWN_MOVE", "Only $PASS is allowed")
            current != playerId -> Decision.Rejected("NOT_YOUR_TURN", "It is not your turn")
            else -> Decision.Accepted(pass(state, playerId, timeout = false))
        }
    }

    /** Время вышло — ход пропускается, как будто игрок сделал PASS. */
    override fun onTimeout(state: SessionState<NothingGameState>): List<SessionEvent> {
        val current = state.turns.currentPlayerId ?: return emptyList()
        return pass(state, current, timeout = true)
    }

    override fun moveOptions(state: SessionState<NothingGameState>, playerId: UUID): List<MoveOption> =
        if (state.turns.currentPlayerId == playerId) listOf(MoveOption(PASS)) else emptyList()

    override fun evolve(state: SessionState<NothingGameState>, event: SessionEvent): SessionState<NothingGameState> =
        when (event.type) {
            MOVED -> state.copy(game = state.game.copy(moves = state.game.moves + 1))
            else -> error("Unknown event ${event.type} of game $CODE")
        }

    private fun pass(state: SessionState<NothingGameState>, playerId: UUID, timeout: Boolean): List<SessionEvent> {
        val turns = TurnsModule.next(state.turns, state.players.map { it.id })
        return buildList {
            add(SessionEvent(MOVED, NothingMoved(playerId, PASS, timeout)))
            add(SessionEvents.turnsChanged(turns))
            if (turns.currentPlayerId == null) add(SessionEvents.finished())
        }
    }

    companion object {
        const val CODE = "nothing"
        const val PASS = "PASS"

        /** Событие игры: игрок сделал ход. */
        const val MOVED = "moved"
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

/** Игровая часть состояния. */
data class NothingGameState(
    /** Сколько ходов сделано (включая пропущенные по времени). */
    val moves: Int,
)

data class NothingMove(
    val type: String,
)

/** Данные события `moved`. */
data class NothingMoved(
    val playerId: UUID,
    /** Вид хода; у «ничто» всегда PASS. */
    val type: String,
    /** Ход сделан автоматически: время хода истекло. */
    val timeout: Boolean,
)
