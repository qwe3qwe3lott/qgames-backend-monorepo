package site.qgames.games.checkers

import site.qgames.games.api.Decision
import site.qgames.games.api.GameRules
import site.qgames.games.api.GameStart
import site.qgames.games.api.SettingsViolation
import site.qgames.games.api.TimeControl
import site.qgames.games.api.TimeControlMode
import site.qgames.games.api.TimeControlRules
import site.qgames.games.api.core.MoveOption
import site.qgames.games.api.core.PlayerState
import site.qgames.games.api.result.ResultModule
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionEvents
import site.qgames.games.api.session.SessionState
import site.qgames.games.api.turns.TurnsModule
import java.util.UUID
import kotlin.random.Random

/**
 * Шашки. Вариант правил — настройка комнаты ([CheckersSettings.variant]); ходы генерируются и проверяются только по
 * флагам [CheckersMoveRules] варианта ([CheckersBoard]), они же уходят клиенту в `meta.moveRules`.
 *
 * Модули: core (варианты ходов: MOVE и RESIGN у того, чей ход, RESIGN у соперника; цвет игрока — поле игры в
 * `players[]`), turns (ход переходит к сопернику, раундов нет), timer (таймаут — поражение), result. Скрытой
 * информации нет, маски не нужны.
 *
 * Ход MOVE — полный путь шашки `{path: [[x, y], …]}` с начальной клеткой; события: `piece-moved`
 * (`PATCH ["state", "pieces", {id}] {square}`), `piece-captured` (`DELETE` на каждую сбитую), `piece-crowned`
 * (`PATCH {kind: KING}`), `last-move-changed` (`PUT ["state", "lastMove"]`), `counters-changed`
 * (`PATCH ["state"] {quietMoves}`), затем `turn-changed` или итог (`result-set`) и `session-finished`. Сдача RESIGN —
 * в любой момент, у любого игрока: `player-resigned` (`INFO {playerId}`). Время хода вышло — `move-timed-out`
 * (`INFO {playerId}`) и поражение.
 *
 * Окончание: у соперника нет шашек (`NO_PIECES`) или ходов (`NO_MOVES`), сдача (`RESIGN`), таймаут (`TIMEOUT`) — победа;
 * [DRAW_QUIET_MOVES] ходов подряд только дамками без взятий — ничья (`DRAW_RULE`).
 */
class CheckersGameRules : GameRules<CheckersGameState, CheckersMove, CheckersSettings> {

    override val code = CODE
    override val minPlayers = 2
    override val maxPlayers = 2
    override val gameStateClass = CheckersGameState::class.java
    override val moveClass = CheckersMove::class.java
    override val settingsClass = CheckersSettings::class.java
    override val playerClass = CheckersPlayer::class.java

    override val timeControl = TimeControlRules(
        modes = setOf(TimeControlMode.NONE, TimeControlMode.PER_MOVE),
        moveSeconds = 5..600,
        default = TimeControl(TimeControlMode.NONE, moveSeconds = null),
    )

    override val defaultSettings = CheckersSettings(CheckersVariant.RUSSIAN)

    override val settingsLimits = CheckersSettingsLimits(VARIANTS)

    override fun validateSettings(settings: CheckersSettings): List<SettingsViolation> =
        if (settings.variant in VARIANTS) {
            emptyList()
        } else {
            listOf(SettingsViolation("variant", "must be one of ${VARIANTS.joinToString()}"))
        }

    override fun moveRules(settings: CheckersSettings) = CheckersMoveRules.of(settings.variant)

    /** Цвета — случайно по seed; расстановка — на тёмных клетках ближних к игроку рядов; первым ходит firstColor. */
    override fun start(players: List<UUID>, seed: Long, settings: CheckersSettings): GameStart<CheckersGameState> {
        val moveRules = moveRules(settings)
        val firstSeatColor = if (Random(seed).nextBoolean()) CheckersColor.WHITE else CheckersColor.BLACK
        val colors = mapOf(players[0] to firstSeatColor, players[1] to firstSeatColor.opponent())
        val first = colors.entries.single { it.value == moveRules.firstColor }.key
        return GameStart(
            game = CheckersGameState(pieces = initialPieces(moveRules.boardSize), lastMove = null, quietMoves = 0),
            turns = TurnsModule.initial(players, rounds = null, first = first),
            players = colors.mapValues { CheckersPlayer(it.value) },
        )
    }

    override fun decide(state: SessionState<CheckersGameState>, settings: CheckersSettings, playerId: UUID, move: CheckersMove): Decision {
        val current = state.turns.currentPlayerId
        return when {
            current == null || state.result != null -> Decision.Rejected(GAME_FINISHED, "The game is already finished")
            move.type == RESIGN -> Decision.Accepted(
                listOf(SessionEvents.info(PLAYER_RESIGNED, mapOf(PLAYER_ID to playerId))) + win(opponentOf(state, playerId), REASON_RESIGN),
            )
            move.type != MOVE -> Decision.Rejected(UNKNOWN_MOVE, "Allowed moves are $MOVE and $RESIGN")
            current != playerId -> Decision.Rejected(NOT_YOUR_TURN, "It is not your turn")
            else -> movePiece(state, moveRules(settings), playerId, move.data)
        }
    }

    /** Время хода вышло — поражение того, чей ход. */
    override fun onTimeout(state: SessionState<CheckersGameState>): List<SessionEvent> {
        val current = state.turns.currentPlayerId ?: return emptyList()
        return listOf(SessionEvents.info(MOVE_TIMED_OUT, mapOf(PLAYER_ID to current))) + win(opponentOf(state, current), REASON_TIMEOUT)
    }

    override fun moveOptions(state: SessionState<CheckersGameState>, playerId: UUID): List<MoveOption> = when {
        state.result != null || state.turns.currentPlayerId == null -> emptyList()
        state.turns.currentPlayerId == playerId -> listOf(MoveOption(MOVE), MoveOption(RESIGN))
        else -> listOf(MoveOption(RESIGN))
    }

    private fun movePiece(
        state: SessionState<CheckersGameState>,
        moveRules: CheckersMoveRules,
        playerId: UUID,
        data: CheckersMoveData?,
    ): Decision {
        val path = data?.path?.map { Square.of(it) ?: return illegal("Every square of the path must be [x, y]") }
            ?: return illegal("The path is required")
        val color = colorOf(state, playerId)
        val board = CheckersBoard(moveRules, state.game.pieces)
        val move = board.legalMoves(color).firstOrNull { it.path == path }
            ?: return illegal("The path is not a legal move: wrong squares, an unfinished capture or a required capture is missed")

        val pieces = board.after(move)
        val quietMoves = if (move.captured.isEmpty() && move.piece.kind == CheckersPieceKind.KING) state.game.quietMoves + 1 else 0
        val opponent = opponentOf(state, playerId)
        val opponentColor = color.opponent()
        val events = buildList {
            add(SessionEvents.patch(PIECE_MOVED, piecePath(move.piece.id), mapOf(SQUARE to path.last().toList())))
            move.captured.forEach { add(SessionEvents.delete(PIECE_CAPTURED, piecePath(it.id))) }
            if (move.crowned) add(SessionEvents.patch(PIECE_CROWNED, piecePath(move.piece.id), mapOf(KIND to CheckersPieceKind.KING)))
            add(
                SessionEvents.put(
                    LAST_MOVE_CHANGED,
                    SessionEvents.path(SessionEvents.STATE, LAST_MOVE),
                    CheckersLastMove(move.piece.id, move.path.map(Square::toList), move.captured.map { it.square }),
                ),
            )
            if (quietMoves != state.game.quietMoves) {
                add(SessionEvents.patch(COUNTERS_CHANGED, SessionEvents.path(SessionEvents.STATE), mapOf(QUIET_MOVES to quietMoves)))
            }
            when {
                pieces.none { it.color == opponentColor } -> addAll(win(playerId, REASON_NO_PIECES))
                CheckersBoard(moveRules, pieces).legalMoves(opponentColor).isEmpty() -> addAll(win(playerId, REASON_NO_MOVES))
                quietMoves >= DRAW_QUIET_MOVES -> addAll(listOf(ResultModule.draw(REASON_DRAW_RULE), SessionEvents.finished()))
                else -> add(TurnsModule.changed(state.turns.copy(currentPlayerId = opponent)))
            }
        }
        return Decision.Accepted(events)
    }

    /** Итог-победа [winnerId] и конец партии. */
    private fun win(winnerId: UUID, reason: String) = listOf(ResultModule.win(winnerId, reason), SessionEvents.finished())

    private fun illegal(message: String) = Decision.Rejected(ILLEGAL_MOVE, message)

    private fun colorOf(state: SessionState<CheckersGameState>, playerId: UUID): CheckersColor = playerFields(player(state, playerId)).color

    private fun opponentOf(state: SessionState<CheckersGameState>, playerId: UUID): UUID = state.players.single { it.id != playerId }.id

    private fun player(state: SessionState<CheckersGameState>, playerId: UUID): PlayerState = state.players.single { it.id == playerId }

    // Поля игрока шашек платформа читает классом playerClass = CheckersPlayer, поэтому приведение безопасно.
    private fun playerFields(player: PlayerState): CheckersPlayer = player.game as CheckersPlayer

    private fun piecePath(id: String) = SessionEvents.path(SessionEvents.STATE, PIECES, SessionEvents.id(id))

    companion object {
        const val CODE = "checkers"

        const val MOVE = "MOVE"
        const val RESIGN = "RESIGN"

        /** Варианты правил, доступные в настройках. */
        val VARIANTS = listOf(CheckersVariant.RUSSIAN)

        /** Ничья: столько ходов подряд (обеих сторон в сумме, по 15 у каждой) только дамками без взятий. */
        const val DRAW_QUIET_MOVES = 30

        const val PIECE_MOVED = "piece-moved"
        const val PIECE_CAPTURED = "piece-captured"
        const val PIECE_CROWNED = "piece-crowned"
        const val LAST_MOVE_CHANGED = "last-move-changed"
        const val COUNTERS_CHANGED = "counters-changed"
        const val PLAYER_RESIGNED = "player-resigned"
        const val MOVE_TIMED_OUT = "move-timed-out"

        const val NOT_YOUR_TURN = "NOT_YOUR_TURN"
        const val UNKNOWN_MOVE = "UNKNOWN_MOVE"
        const val ILLEGAL_MOVE = "ILLEGAL_MOVE"
        const val GAME_FINISHED = "GAME_FINISHED"

        const val REASON_NO_PIECES = "NO_PIECES"
        const val REASON_NO_MOVES = "NO_MOVES"
        const val REASON_RESIGN = "RESIGN"
        const val REASON_TIMEOUT = "TIMEOUT"
        const val REASON_DRAW_RULE = "DRAW_RULE"

        private const val PIECES = "pieces"
        private const val LAST_MOVE = "lastMove"
        private const val QUIET_MOVES = "quietMoves"
        private const val SQUARE = "square"
        private const val KIND = "kind"
        private const val PLAYER_ID = "playerId"

        /** Начальная расстановка: по `boardSize / 2 - 1` рядов тёмных клеток у каждой стороны. */
        fun initialPieces(boardSize: Int): List<CheckersPiece> {
            val rows = boardSize / 2 - 1
            val dark = { x: Int, y: Int -> (x + y) % 2 == 0 }
            val white = (1..rows).flatMap { y -> (1..boardSize).filter { dark(it, y) }.map { x -> Square(x, y) } }
            val black = (boardSize downTo boardSize - rows + 1).flatMap { y -> (boardSize downTo 1).filter { dark(it, y) }.map { x -> Square(x, y) } }
            return white.mapIndexed { i, s -> CheckersPiece("w${i + 1}", CheckersColor.WHITE, CheckersPieceKind.MAN, s.toList()) } +
                black.mapIndexed { i, s -> CheckersPiece("b${i + 1}", CheckersColor.BLACK, CheckersPieceKind.MAN, s.toList()) }
        }
    }
}
