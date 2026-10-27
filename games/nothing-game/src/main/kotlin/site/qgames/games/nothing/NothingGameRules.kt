package site.qgames.games.nothing

import java.util.UUID
import site.qgames.games.core.Decision
import site.qgames.games.core.GameRules
import site.qgames.games.core.GameStart
import site.qgames.games.core.MoveOption
import site.qgames.games.core.SessionEvent
import site.qgames.games.core.SessionEvents
import site.qgames.games.core.SessionState
import site.qgames.games.core.SettingsViolation
import site.qgames.games.core.TimeControlRules
import site.qgames.games.core.TimeControlSettings
import site.qgames.games.core.Turns

/**
 * «Игра в ничто»: игроки по очереди делают ход PASS, после [NothingAdvancedSettings.rounds] кругов партия заканчивается
 * без победителя. Нужна, чтобы проверить движок целиком: очерёдность, журнал, таймеры, конец партии.
 *
 * Настройки: simple — число кругов и пресет времени ([NothingTime]), advanced — число кругов и часы.
 *
 * Core: варианты ходов (PASS у того, чей ход), очерёдность (ход по кругу, раунды из настроек), часы, итог
 * (итог не ставится: `result` всегда null).
 * Собственный код события один — `player-passed`: ход игрока — `PATCH ["state"] {moves}` (счётчик ходов), пропуск по
 * истечении времени — `INFO {playerId, timeout: true}` (состояние игры не меняется). Скрытой информации нет, маски не
 * нужны.
 */
class NothingGameRules : GameRules<NothingGameState, NothingMove, NothingSimpleSettings, NothingAdvancedSettings> {

    override val code = CODE
    override val minPlayers = 1
    override val maxPlayers = 4
    override val gameStateClass = NothingGameState::class.java
    override val moveClass = NothingMove::class.java
    override val simpleClass = NothingSimpleSettings::class.java
    override val advancedClass = NothingAdvancedSettings::class.java

    override val timeControl = TimeControlRules(optional = true, baseSeconds = 5..3600, incrementSeconds = 0..60)

    override val defaultSimple = NothingSimpleSettings(rounds = 3, time = NothingTime.NONE)

    override val settingsLimits = NothingSettingsLimits(rounds = ROUNDS, timePresets = TIME_PRESETS)

    override fun validateSimple(settings: NothingSimpleSettings): List<SettingsViolation> = validateRounds(settings.rounds)

    override fun validateAdvanced(settings: NothingAdvancedSettings): List<SettingsViolation> = validateRounds(settings.rounds)

    /** Без таймера часы заполняются значениями пресета CLASSIC — на случай включения в advanced. */
    override fun toAdvanced(settings: NothingSimpleSettings): NothingAdvancedSettings {
        val preset = TIME_PRESETS.single { it.time == NothingTime.CLASSIC }
        return NothingAdvancedSettings(
            rounds = settings.rounds,
            timeControl = TimeControlSettings(settings.time != NothingTime.NONE, preset.baseSeconds, preset.incrementSeconds),
        )
    }

    override fun clock(settings: NothingAdvancedSettings) = settings.timeControl

    override fun start(players: List<UUID>, seed: Long, settings: NothingAdvancedSettings) =
        GameStart(NothingGameState(moves = 0), Turns.initial(players, settings.rounds, first = players.first()))

    override fun decide(state: SessionState<NothingGameState>, settings: NothingAdvancedSettings, playerId: UUID, move: NothingMove): Decision {
        val current = state.turns.currentPlayerId
        return when {
            current == null -> Decision.Rejected("GAME_FINISHED", "The game is already finished")
            move.type != PASS -> Decision.Rejected("UNKNOWN_MOVE", "Only $PASS is allowed")
            current != playerId -> Decision.Rejected("NOT_YOUR_TURN", "It is not your turn")
            else -> Decision.Accepted(
                pass(state, SessionEvents.patch(PLAYER_PASSED, SessionEvents.path(SessionEvents.STATE), mapOf(MOVES to state.game.moves + 1))),
            )
        }
    }

    /**
     * Время вышло — ход пропускается: очередь переходит дальше, счётчик ходов не меняется. У игрока без запаса (и без
     * добавки) следующие ходы тоже пропускаются по таймауту.
     */
    override fun onTimeout(state: SessionState<NothingGameState>): List<SessionEvent> {
        val current = state.turns.currentPlayerId ?: return emptyList()
        return pass(state, SessionEvents.info(PLAYER_PASSED, mapOf("playerId" to current, "timeout" to true)))
    }

    override fun moveOptions(state: SessionState<NothingGameState>, playerId: UUID): List<MoveOption> =
        if (state.turns.currentPlayerId == playerId) listOf(MoveOption(PASS)) else emptyList()

    private fun validateRounds(rounds: Int): List<SettingsViolation> =
        if (rounds in ROUNDS) {
            emptyList()
        } else {
            listOf(SettingsViolation("rounds", "must be between ${ROUNDS.first} and ${ROUNDS.last}"))
        }

    private fun pass(state: SessionState<NothingGameState>, passed: SessionEvent): List<SessionEvent> {
        val turns = Turns.next(state.turns, state.players.map { it.id })
        return buildList {
            add(passed)
            add(Turns.changed(turns))
            if (turns.currentPlayerId == null) add(SessionEvents.finished())
        }
    }

    companion object {
        const val CODE = "nothing"
        const val PASS = "PASS"

        /** Событие игры: игрок пропустил ход (сам или по истечении времени). */
        const val PLAYER_PASSED = "player-passed"

        private const val MOVES = "moves"
        private val ROUNDS = 1..50

        /** Пресеты времени simple-настроек. */
        val TIME_PRESETS = listOf(NothingTimePreset(NothingTime.CLASSIC, baseSeconds = 30, incrementSeconds = 5))
    }
}

/** Пресет времени simple-настроек. */
enum class NothingTime {
    /** Без таймера. */
    NONE,
    CLASSIC,
}

/** Simple-настройки: число кругов и пресет времени. */
data class NothingSimpleSettings(
    val rounds: Int,
    val time: NothingTime,
)

/** Advanced-настройки партии: число кругов и часы. */
data class NothingAdvancedSettings(
    val rounds: Int,
    val timeControl: TimeControlSettings,
)

/** Часы пресета [time]. */
data class NothingTimePreset(
    val time: NothingTime,
    val baseSeconds: Int,
    val incrementSeconds: Int,
)

/** Границы настроек для формы и часы пресетов. */
data class NothingSettingsLimits(
    val rounds: IntRange,
    val timePresets: List<NothingTimePreset>,
)

/** Игровая часть состояния (в документе — поле `state.moves`). */
data class NothingGameState(
    /** Сколько ходов сделали игроки; пропуски по истечении времени не считаются. */
    val moves: Int,
)

data class NothingMove(
    val type: String,
)
