package site.qgames.games.checkers

import java.time.Instant
import java.util.UUID
import java.util.stream.Stream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.games.checkers.CheckersColor.BLACK
import site.qgames.games.checkers.CheckersColor.WHITE
import site.qgames.games.core.GameRules
import site.qgames.games.core.MoveOption
import site.qgames.games.core.MoveOutcome
import site.qgames.games.core.PathSegment
import site.qgames.games.core.ResultOutcome
import site.qgames.games.core.ResultState
import site.qgames.games.core.SessionDocument
import site.qgames.games.core.SessionEngine
import site.qgames.games.core.SessionEvent
import site.qgames.games.core.SessionEventMethod
import site.qgames.games.core.SessionEvents
import site.qgames.games.core.SessionStatus
import site.qgames.games.core.TimeControl
import site.qgames.games.core.TurnsState
import site.qgames.games.core.ViewerKey

/**
 * Партия шашек на платформе: генерация и проверка ходов по флагам правил, события, окончания и маскировка — чистые
 * функции, без сервиса. Основные сценарии — на русском варианте, для международного и английского — их отличия.
 * Позиции задаются поверх начального журнала событиями-патчами.
 */
class CheckersSessionScenariosTest {

    private val checkers = CheckersGameRules()

    @Suppress("UNCHECKED_CAST")
    private val rules = checkers as GameRules<Any, Any, Any>

    private val codec = JsonSessionCodec()

    private val engine = SessionEngine(codec)

    private val start = engine.start(rules, listOf(ALICE, BOB), SEED, RUSSIAN, NO_TIMER, NOW)

    private val white = idOf(fold(start), WHITE)

    private val black = idOf(fold(start), BLACK)

    @Test
    fun `start sets colors by seed, the initial position, move rules and white moves first`() {
        val started = start.single()
        @Suppress("UNCHECKED_CAST")
        val document = started.value as Map<String, Any?>
        val session = fold(start).session

        assertThat(started.code).isEqualTo("session-started")
        assertThat(document["meta"]).isEqualTo(
            mapOf(
                "game" to mapOf(
                    "code" to "checkers",
                    "rules" to mapOf(
                        "timeControl" to null,
                        "gameSettings" to mapOf("gameCode" to "checkers", "variant" to "RUSSIAN"),
                    ),
                ),
                "moveRules" to mapOf(
                    "boardSize" to 8,
                    "firstColor" to "WHITE",
                    "menMoveBackward" to false,
                    "menCaptureBackward" to true,
                    "flyingKings" to true,
                    "captureRequired" to true,
                    "maxCaptureRequired" to false,
                    "promoteMidCapture" to true,
                    "removeCapturedAfterMove" to true,
                ),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val state = document["state"] as Map<String, Any?>
        assertThat(state.keys).containsExactlyInAnyOrder(
            "players", "timer", "turns", "result", "pieces", "lastMove", "quietMoves", "materialMoves", "positionHistory", "drawOfferedBy",
        )
        assertThat(state["players"]).isEqualTo(
            listOf(ALICE, BOB).map {
                val color = if (it == white) "WHITE" else "BLACK"
                val options = if (it == white) listOf(option("MOVE"), option("RESIGN")) else listOf(option("RESIGN"))
                mapOf("id" to it.toString(), "moveOptions" to options, "color" to color)
            },
        )
        assertThat(session.state.turns).isEqualTo(TurnsState(white, round = 1, rounds = null))
        assertThat(session.state.result).isNull()
        val game = session.state.game as CheckersGameState
        assertThat(game.lastMove).isNull()
        assertThat(game.quietMoves).isZero()
        assertThat(game.pieces).hasSize(24)
        assertThat(game.pieces.filter { it.color == WHITE }.map { it.id }).containsExactlyElementsOf((1..12).map { "w$it" })
        assertThat(game.pieces.filter { it.color == BLACK }.map { it.id }).containsExactlyElementsOf((1..12).map { "b$it" })
        assertThat(game.pieces).allMatch { it.kind == CheckersPieceKind.MAN && (it.square[0] + it.square[1]) % 2 == 0 }
        assertThat(game.pieces.filter { it.color == WHITE }.map { it.square[1] }.toSet()).containsExactlyInAnyOrder(1, 2, 3)
        assertThat(game.pieces.filter { it.color == BLACK }.map { it.square[1] }.toSet()).containsExactlyInAnyOrder(6, 7, 8)
        assertThat(game.pieces.first()).isEqualTo(CheckersPiece("w1", WHITE, CheckersPieceKind.MAN, listOf(1, 1)))
    }

    @Test
    fun `colors are random by seed and the same seed gives the same colors`() {
        val whiteBySeed = (1L..20L).map { seed -> idOf(fold(engine.start(rules, listOf(ALICE, BOB), seed, RUSSIAN, NO_TIMER, NOW)), WHITE) }

        assertThat(whiteBySeed.toSet()).containsExactlyInAnyOrder(ALICE, BOB)
        assertThat(idOf(fold(engine.start(rules, listOf(ALICE, BOB), SEED, RUSSIAN, NO_TIMER, NOW)), WHITE)).isEqualTo(white)
    }

    @Test
    fun `all variants pass the settings check`() {
        assertThat(CheckersVariant.entries.flatMap { checkers.validateSettings(CheckersSettings(it)) }).isEmpty()
        assertThat(checkers.settingsLimits.variants).isEqualTo(CheckersVariant.entries)
    }

    @Test
    fun `every variant has classic, rapid and blitz presets within the clock limits`() {
        val presets = checkers.settingsLimits.timePresets

        CheckersVariant.entries.forEach { variant ->
            assertThat(presets.filter { it.variant == variant }.map { it.kind }).containsExactly(*CheckersTimePresetKind.entries.toTypedArray())
        }
        assertThat(presets).allMatch { it.baseSeconds in checkers.timeControl.baseSeconds && it.incrementSeconds in checkers.timeControl.incrementSeconds }
        assertThat(presets.single { it.variant == CheckersVariant.RUSSIAN && it.kind == CheckersTimePresetKind.CLASSIC })
            .isEqualTo(CheckersTimePreset(CheckersVariant.RUSSIAN, CheckersTimePresetKind.CLASSIC, 1800, 30))
    }

    @Test
    fun `a draw offered with a move is accepted by the opponent in their turn`() {
        val journal = position(listOf(w("w1", 3, 3), b("b1", 8, 8)))

        val offered = accepted(journal, white, offer(3 to 3, 4 to 4))
        assertThat(offered.single { it.code == "draw-offer-changed" }.value).isEqualTo(mapOf("drawOfferedBy" to white.toString()))
        val afterOffer = fold(journal + offered).session
        assertThat((afterOffer.state.game as CheckersGameState).drawOfferedBy).isEqualTo(white)
        assertThat(afterOffer.state.players.single { it.id == black }.moveOptions.map { it.type }).containsExactly("MOVE", "ACCEPT_DRAW", "RESIGN")

        val events = accepted(journal + offered, black, CheckersMove("ACCEPT_DRAW"))
        assertThat(events.first().code).isEqualTo("draw-accepted")
        assertThat(fold(journal + offered + events).session.state.result).isEqualTo(ResultState(ResultOutcome.DRAW, emptyList(), "DRAW_AGREED"))
    }

    @Test
    fun `a move of the opponent declines the draw offer`() {
        val journal = position(listOf(w("w1", 3, 3), b("b1", 8, 8)))
        val offered = journal + accepted(journal, white, offer(3 to 3, 4 to 4))

        val declined = accepted(offered, black, 8 to 8, 7 to 7)

        assertThat(declined.single { it.code == "draw-offer-changed" }.value).isEqualTo(mapOf("drawOfferedBy" to null))
        val session = fold(offered + declined).session
        assertThat((session.state.game as CheckersGameState).drawOfferedBy).isNull()
        assertThat(session.state.players.single { it.id == white }.moveOptions.map { it.type }).containsExactly("MOVE", "RESIGN")
    }

    @Test
    fun `a draw can be accepted only when the opponent offered it`() {
        val journal = position(listOf(w("w1", 3, 3), b("b1", 8, 8)))
        val offered = journal + accepted(journal, white, offer(3 to 3, 4 to 4))

        assertThat(rejected(journal, white, CheckersMove("ACCEPT_DRAW"))).isEqualTo("ILLEGAL_MOVE")
        assertThat(rejected(offered, white, CheckersMove("ACCEPT_DRAW"))).isEqualTo("NOT_YOUR_TURN")
    }

    @Test
    fun `the same position for the third time is a draw`() {
        val cycle = listOf(white to listOf(1 to 1, 2 to 2), black to listOf(8 to 2, 7 to 3), white to listOf(2 to 2, 1 to 1), black to listOf(7 to 3, 8 to 2))
        val journal = position(listOf(wk("w1", 1, 1), bk("b1", 8, 2))).toMutableList()

        // Позиция начала повторится второй раз после первого круга и третий — после второго.
        (cycle + cycle.dropLast(1)).forEach { (player, path) ->
            journal += accepted(journal, player, *path.toTypedArray())
            assertThat(fold(journal).session.state.result).isNull()
        }
        val events = accepted(journal, black, 7 to 3, 8 to 2)

        assertThat(fold(journal + events).session.state.result).isEqualTo(ResultState(ResultOutcome.DRAW, emptyList(), "REPETITION"))
    }

    @Test
    fun `a capture or a move of a man resets the position history`() {
        val journal = position(listOf(w("w1", 3, 3), wk("w2", 1, 1), bk("b1", 8, 2)))

        val events = accepted(journal, white, 3 to 3, 4 to 4)

        assertThat((fold(journal + events).session.state.game as CheckersGameState).positionHistory).hasSize(1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endgames")
    fun `endgame rules draw at their move limit`(case: String, settings: CheckersSettings, pieces: List<CheckersPiece>, path: List<Pair<Int, Int>>, limit: Int) {
        val mover = if (CheckersMoveRules.of(settings.variant).firstColor == WHITE) white else black

        val before = position(pieces, materialMoves = limit - 2, settings = settings)
        assertThat(fold(before + accepted(before, mover, *path.toTypedArray())).session.state.result).isNull()

        val atLimit = position(pieces, materialMoves = limit - 1, settings = settings)
        val result = fold(atLimit + accepted(atLimit, mover, *path.toTypedArray())).session.state.result
        assertThat(result).isEqualTo(ResultState(ResultOutcome.DRAW, emptyList(), "ENDGAME_RULE"))
    }

    @Test
    fun `english draughts have no endgame rules`() {
        val journal = position(listOf(wk("w1", 1, 1), bk("b1", 8, 2)), materialMoves = 500, settings = ENGLISH)

        assertThat(fold(journal + accepted(journal, black, 8 to 2, 7 to 3)).session.state.result).isNull()
    }

    @Test
    fun `a capture or a crowning resets the endgame counter`() {
        val journal = position(listOf(w("w1", 3, 3), b("b1", 4, 4), bk("b2", 8, 2)), materialMoves = 7)

        val events = accepted(journal, white, 3 to 3, 5 to 5)

        assertThat((fold(journal + events).session.state.game as CheckersGameState).materialMoves).isZero()
    }
    @Test
    fun `international - 10x10 board with four rows of men, white moves first`() {
        val document = fold(engine.start(rules, listOf(ALICE, BOB), SEED, INTERNATIONAL, NO_TIMER, NOW))
        val game = document.session.state.game as CheckersGameState

        assertThat(document.session.state.turns.currentPlayerId).isEqualTo(white)
        assertThat(game.pieces).hasSize(40)
        assertThat(game.pieces).allMatch { it.kind == CheckersPieceKind.MAN && (it.square[0] + it.square[1]) % 2 == 0 }
        assertThat(game.pieces.filter { it.color == WHITE }.map { it.square[1] }.toSet()).containsExactlyInAnyOrder(1, 2, 3, 4)
        assertThat(game.pieces.filter { it.color == BLACK }.map { it.square[1] }.toSet()).containsExactlyInAnyOrder(7, 8, 9, 10)
        assertThat(game.pieces.filter { it.color == WHITE }).hasSize(20)
    }

    @Test
    fun `international - only the branch with the most captures is allowed`() {
        val pieces = listOf(w("w1", 3, 3), b("b1", 4, 4), b("b2", 6, 6), b("b3", 2, 4), b("b4", 1, 7))
        val journal = position(pieces, settings = INTERNATIONAL)

        assertThat(rejection(journal, white, 3 to 3, 1 to 5)).isEqualTo("ILLEGAL_MOVE")
        accepted(journal, white, 3 to 3, 5 to 5, 7 to 7)
        // В русских можно взять любую ветку.
        accepted(position(pieces), white, 3 to 3, 1 to 5)
    }

    @Test
    fun `international - a man passing the last row in a capture stays a man and captures on backward`() {
        val journal = position(listOf(w("w1", 2, 8), b("b1", 3, 9), b("b2", 5, 9), b("b3", 10, 2)), settings = INTERNATIONAL)

        assertThat(rejection(journal, white, 2 to 8, 4 to 10)).isEqualTo("ILLEGAL_MOVE")
        assertThat(rejection(journal, white, 2 to 8, 4 to 10, 7 to 7)).isEqualTo("ILLEGAL_MOVE")
        val events = accepted(journal, white, 2 to 8, 4 to 10, 6 to 8)

        assertThat(events.map { it.code }).doesNotContain("piece-crowned")
        assertThat(game(journal + events).pieces.single { it.id == "w1" }.kind).isEqualTo(CheckersPieceKind.MAN)
    }

    @Test
    fun `international - a man ending the move on the last row becomes a king`() {
        val journal = position(listOf(w("w1", 2, 8), b("b1", 3, 9), b("b2", 10, 2)), settings = INTERNATIONAL)

        val events = accepted(journal, white, 2 to 8, 4 to 10)

        assertThat(events.map { it.code }).contains("piece-crowned")
        assertThat(game(journal + events).pieces.single { it.id == "w1" }.kind).isEqualTo(CheckersPieceKind.KING)
    }

    @Test
    fun `english - black moves first`() {
        val document = fold(engine.start(rules, listOf(ALICE, BOB), SEED, ENGLISH, NO_TIMER, NOW))

        assertThat(document.session.state.turns.currentPlayerId).isEqualTo(black)
        assertThat((document.session.state.game as CheckersGameState).pieces).hasSize(24)
    }

    @Test
    fun `english - a man does not capture backward`() {
        // Белая шашка позади чёрной: в русских бой назад обязателен, в английских его нет.
        val pieces = listOf(b("b1", 4, 4), w("w1", 5, 5), wk("w2", 1, 7))

        assertThat(rejection(position(pieces, settings = ENGLISH), black, 4 to 4, 6 to 6)).isEqualTo("ILLEGAL_MOVE")
        accepted(position(pieces, settings = ENGLISH), black, 4 to 4, 3 to 3)
    }

    @Test
    fun `english - a king moves and captures one square in any direction`() {
        val far = position(listOf(bk("b1", 1, 1), w("w1", 3, 3), wk("w2", 8, 8)), settings = ENGLISH)
        assertThat(rejection(far, black, 1 to 1, 4 to 4)).isEqualTo("ILLEGAL_MOVE")
        accepted(far, black, 1 to 1, 2 to 2)

        val near = position(listOf(bk("b1", 2, 2), w("w1", 3, 3), wk("w2", 8, 8)), settings = ENGLISH)
        assertThat(rejection(near, black, 2 to 2, 5 to 5)).isEqualTo("ILLEGAL_MOVE")
        accepted(near, black, 2 to 2, 4 to 4)
    }

    @Test
    fun `english - reaching the last row in a capture crowns and ends the move`() {
        val journal = position(listOf(b("b1", 6, 3), w("w1", 5, 2), w("w2", 3, 2), wk("w3", 8, 8)), settings = ENGLISH)

        assertThat(rejection(journal, black, 6 to 3, 4 to 1, 2 to 3)).isEqualTo("ILLEGAL_MOVE")
        val events = accepted(journal, black, 6 to 3, 4 to 1)

        assertThat(events.map { it.code }).contains("piece-crowned")
        assertThat(game(journal + events).pieces.single { it.id == "b1" }.kind).isEqualTo(CheckersPieceKind.KING)
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("drawLimits")
    fun `quiet king moves draw at the limit of the variant`(variant: CheckersVariant, limit: Int) {
        val pieces = listOf(wk("w1", 1, 1), bk("b1", 8, 2))
        val settings = CheckersSettings(variant)
        val mover = if (CheckersMoveRules.of(variant).firstColor == WHITE) white else black
        val path = if (mover == white) arrayOf(1 to 1, 2 to 2) else arrayOf(8 to 2, 7 to 3)

        val before = accepted(position(pieces, quietMoves = limit - 2, settings = settings), mover, *path)
        assertThat(before.map { it.code }).contains("turn-changed")

        val atLimit = position(pieces, quietMoves = limit - 1, settings = settings)
        assertThat(fold(atLimit + accepted(atLimit, mover, *path)).session.state.result).isEqualTo(ResultState(ResultOutcome.DRAW, emptyList(), "DRAW_RULE"))
    }

    @Test
    fun `quiet move of a man produces piece, last move and turn events`() {
        val journal = position(listOf(w("w1", 3, 3), b("b1", 8, 8)))

        val events = accepted(journal, white, 3 to 3, 4 to 4)

        assertThat(events.map { Triple(it.code, it.method, it.path) }).containsExactly(
            Triple("piece-moved", SessionEventMethod.PATCH, piecePath("w1")),
            Triple("last-move-changed", SessionEventMethod.PUT, listOf(key("state"), key("lastMove"))),
            Triple("counters-changed", SessionEventMethod.PATCH, listOf(key("state"))),
            Triple("position-recorded", SessionEventMethod.PUT, listOf(key("state"), key("positionHistory"))),
            Triple("turn-changed", SessionEventMethod.PUT, listOf(key("state"), key("turns"))),
            Triple("move-options-changed", SessionEventMethod.PUT, playerOptions(ALICE)),
            Triple("move-options-changed", SessionEventMethod.PUT, playerOptions(BOB)),
        )
        assertThat(events[0].value).isEqualTo(mapOf("square" to listOf(4, 4)))
        assertThat(events[1].value).isEqualTo(mapOf("pieceId" to "w1", "path" to listOf(listOf(3, 3), listOf(4, 4)), "captured" to emptyList<Any>()))
        // Ход простой необратим: история повторений начинается заново с новой позиции.
        assertThat(events[3].value).isEqualTo(listOf("B|BM8,8;WM4,4"))
        val after = fold(journal + events).session
        assertThat(after.state.turns.currentPlayerId).isEqualTo(black)
        assertThat(after.state.players.single { it.id == black }.moveOptions).containsExactly(MoveOption("MOVE"), MoveOption("RESIGN"))
        assertThat(after.state.players.single { it.id == white }.moveOptions).containsExactly(MoveOption("RESIGN"))
    }

    @Test
    fun `a man moves one square forward only, a flying king moves any free distance`() {
        val men = position(listOf(w("w1", 3, 3), b("b1", 8, 8)))
        assertThat(rejection(men, white, 3 to 3, 2 to 2)).isEqualTo("ILLEGAL_MOVE")
        assertThat(rejection(men, white, 3 to 3, 5 to 5)).isEqualTo("ILLEGAL_MOVE")
        accepted(men, white, 3 to 3, 2 to 4)

        val kings = position(listOf(wk("w1", 1, 1), w("w2", 6, 6), b("b1", 8, 2)))
        assertThat(rejection(kings, white, 1 to 1, 7 to 7)).isEqualTo("ILLEGAL_MOVE")
        val events = accepted(kings, white, 1 to 1, 5 to 5)
        assertThat(events.map { it.code }).contains("counters-changed")
        assertThat(events.single { it.code == "counters-changed" }.value).isEqualTo(mapOf("quietMoves" to 1, "materialMoves" to 1))
    }

    @Test
    fun `capture is required and removes the captured piece`() {
        val journal = position(listOf(w("w1", 3, 3), w("w2", 7, 3), b("b1", 4, 4), b("b2", 8, 8)))

        assertThat(rejection(journal, white, 7 to 3, 8 to 4)).isEqualTo("ILLEGAL_MOVE")
        val events = accepted(journal, white, 3 to 3, 5 to 5)

        assertThat(events.take(3).map { Triple(it.code, it.method, it.path) }).containsExactly(
            Triple("piece-moved", SessionEventMethod.PATCH, piecePath("w1")),
            Triple("piece-captured", SessionEventMethod.DELETE, piecePath("b1")),
            Triple("last-move-changed", SessionEventMethod.PUT, listOf(key("state"), key("lastMove"))),
        )
        assertThat(events[2].value).isEqualTo(
            mapOf("pieceId" to "w1", "path" to listOf(listOf(3, 3), listOf(5, 5)), "captured" to listOf(listOf(4, 4))),
        )
        assertThat(game(journal + events).pieces.map { it.id }).containsExactly("w1", "w2", "b2")
    }

    @Test
    fun `a man captures backward`() {
        val journal = position(listOf(w("w1", 5, 5), b("b1", 4, 4), b("b2", 8, 8)))

        accepted(journal, white, 5 to 5, 3 to 3)
    }

    @Test
    fun `multiple capture - any branch, but the capture cannot stop while it can go on`() {
        val journal = position(listOf(w("w1", 1, 1), b("b1", 2, 2), b("b2", 4, 4), b("b3", 6, 6), b("b4", 2, 4)))

        assertThat(rejection(journal, white, 1 to 1, 3 to 3)).isEqualTo("ILLEGAL_MOVE")
        assertThat(rejection(journal, white, 1 to 1, 3 to 3, 5 to 5)).isEqualTo("ILLEGAL_MOVE")
        val longest = accepted(journal, white, 1 to 1, 3 to 3, 5 to 5, 7 to 7)
        assertThat(longest.filter { it.code == "piece-captured" }.map { it.path }).containsExactly(piecePath("b1"), piecePath("b2"), piecePath("b3"))
        val shorter = accepted(journal, white, 1 to 1, 3 to 3, 1 to 5)
        assertThat(shorter.filter { it.code == "piece-captured" }.map { it.path }).containsExactly(piecePath("b1"), piecePath("b4"))
    }

    @Test
    fun `a king after a jump must land where the capture goes on`() {
        val journal = position(listOf(wk("w1", 1, 1), b("b1", 3, 3), b("b2", 6, 4), b("b3", 8, 8)))

        assertThat(rejection(journal, white, 1 to 1, 4 to 4)).isEqualTo("ILLEGAL_MOVE")
        assertThat(rejection(journal, white, 1 to 1, 6 to 6)).isEqualTo("ILLEGAL_MOVE")
        accepted(journal, white, 1 to 1, 5 to 5, 8 to 2)
        accepted(journal, white, 1 to 1, 5 to 5, 7 to 3)
    }

    @Test
    fun `turkish strike - captured pieces stay until the end of the move and block the way`() {
        val pieces = listOf(wk("w1", 1, 1), b("b1", 4, 4), b("b2", 7, 5), b("b3", 7, 3), b("b4", 2, 6))
        val journal = position(pieces)

        assertThat(rejection(journal, white, 1 to 1, 6 to 6, 8 to 4, 6 to 2, 1 to 7)).isEqualTo("ILLEGAL_MOVE")
        val events = accepted(journal, white, 1 to 1, 6 to 6, 8 to 4, 6 to 2)
        assertThat(events.filter { it.code == "piece-captured" }.map { it.path })
            .containsExactly(piecePath("b1"), piecePath("b2"), piecePath("b3"))

        // Без турецкого удара сбитая сразу снимается, и через её клетку бой продолжается.
        val removing = CheckersBoard(CheckersMoveRules.RUSSIAN.copy(removeCapturedAfterMove = false), pieces).legalMoves(WHITE)
        assertThat(removing.map { it.path.map(Square::toList) }).contains(squares(1 to 1, 6 to 6, 8 to 4, 6 to 2, 1 to 7))
        assertThat(removing.map { it.path.map(Square::toList) }).doesNotContain(squares(1 to 1, 6 to 6, 8 to 4, 6 to 2))
    }

    @Test
    fun `a man crowned in the middle of a capture captures on as a king`() {
        val journal = position(listOf(w("w1", 2, 6), b("b1", 3, 7), b("b2", 7, 5), b("b3", 1, 7)))

        assertThat(rejection(journal, white, 2 to 6, 4 to 8)).isEqualTo("ILLEGAL_MOVE")
        val events = accepted(journal, white, 2 to 6, 4 to 8, 8 to 4)

        assertThat(events.map { it.code }.take(4)).containsExactly("piece-moved", "piece-captured", "piece-captured", "piece-crowned")
        assertThat(events[3].path).isEqualTo(piecePath("w1"))
        assertThat(events[3].value).isEqualTo(mapOf("kind" to "KING"))
        assertThat(game(journal + events).pieces.single { it.id == "w1" })
            .isEqualTo(CheckersPiece("w1", WHITE, CheckersPieceKind.KING, listOf(8, 4)))
    }

    @Test
    fun `a man reaching the last row becomes a king at the end of the move`() {
        val journal = position(listOf(w("w1", 3, 7), b("b1", 6, 2)))

        val events = accepted(journal, white, 3 to 7, 4 to 8)

        assertThat(events.map { it.code }.take(3)).containsExactly("piece-moved", "piece-crowned", "last-move-changed")
        assertThat(game(journal + events).pieces.single { it.id == "w1" }.kind).isEqualTo(CheckersPieceKind.KING)
    }

    @Test
    fun `quiet moves count only king moves without captures`() {
        val journal = position(listOf(wk("w1", 1, 1), w("w2", 3, 1), b("b1", 8, 8), b("b2", 6, 8)), quietMoves = 5)

        assertThat(game(journal + accepted(journal, white, 1 to 1, 2 to 2)).quietMoves).isEqualTo(6)
        val manMove = accepted(journal, white, 3 to 1, 4 to 2)
        assertThat(manMove.single { it.code == "counters-changed" }.value).isEqualTo(mapOf("quietMoves" to 0, "materialMoves" to 1))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endings")
    fun `the game ends with a result`(case: String, pieces: List<CheckersPiece>, quietMoves: Int, path: List<Pair<Int, Int>>, outcome: ResultOutcome, reason: String) {
        val journal = position(pieces, quietMoves = quietMoves)

        val events = accepted(journal, white, *path.toTypedArray())

        assertThat(events.map { it.code }).doesNotContain("turn-changed")
        assertThat(events.map { it.code }.takeLast(4)).containsExactly("result-set", "move-options-changed", "move-options-changed", "session-finished")
        val finished = fold(journal + events).session
        assertThat(finished.status).isEqualTo(SessionStatus.FINISHED)
        val winners = if (outcome == ResultOutcome.WIN) listOf(white) else emptyList()
        assertThat(finished.state.result).isEqualTo(ResultState(outcome, winners, reason))
        assertThat(finished.state.players.map { it.moveOptions }).containsExactly(emptyList(), emptyList())
    }

    @Test
    fun `either player can resign at any time`() {
        val journal = position(listOf(w("w1", 3, 3), b("b1", 8, 8)), timeControl = TimeControl(30, 0))

        val events = (engine.move(rules, fold(journal), black, CheckersMove("RESIGN"), NOW) as MoveOutcome.Accepted).events

        assertThat(events.first().code).isEqualTo("player-resigned")
        assertThat(events.first().method).isEqualTo(SessionEventMethod.INFO)
        assertThat(events.first().value).isEqualTo(mapOf("playerId" to black.toString()))
        assertThat(events.map { it.code }.takeLast(2)).containsExactly("timer-stopped", "session-finished")
        assertThat(fold(journal + events).session.state.result).isEqualTo(ResultState(ResultOutcome.WIN, listOf(white), "RESIGN"))
    }

    @Test
    fun `timeout loses the game`() {
        val journal = position(listOf(w("w1", 3, 3), b("b1", 8, 8)), timeControl = TimeControl(10, 0))

        val events = engine.timeout(rules, fold(journal), NOW.plusSeconds(10))

        assertThat(events.first().code).isEqualTo("move-timed-out")
        assertThat(events.first().method).isEqualTo(SessionEventMethod.INFO)
        assertThat(events.first().value).isEqualTo(mapOf("playerId" to white.toString()))
        assertThat(events.map { it.code }.takeLast(2)).containsExactly("timer-stopped", "session-finished")
        val finished = fold(journal + events).session
        assertThat(finished.status).isEqualTo(SessionStatus.FINISHED)
        assertThat(finished.state.result).isEqualTo(ResultState(ResultOutcome.WIN, listOf(black), "TIMEOUT"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    fun `rules reject moves`(case: String, byWhite: Boolean, move: CheckersMove, reason: String) {
        val journal = position(listOf(w("w1", 3, 3), b("b1", 8, 8)))

        val outcome = engine.move(rules, fold(journal), if (byWhite) white else black, move, NOW)

        assertThat(outcome).isInstanceOf(MoveOutcome.Rejected::class.java)
        assertThat((outcome as MoveOutcome.Rejected).code).isEqualTo(reason)
    }

    @Test
    fun `moves after the result are rejected as GAME_FINISHED`() {
        val journal = position(listOf(w("w1", 3, 3), b("b1", 4, 4)))
        val finished = fold(journal + accepted(journal, white, 3 to 3, 5 to 5)).session
        @Suppress("UNCHECKED_CAST")
        val state = finished.state as site.qgames.games.core.SessionState<CheckersGameState>

        val decision = checkers.decide(state, RUSSIAN, white, CheckersMove("RESIGN"))

        assertThat(decision).isEqualTo(site.qgames.games.core.Decision.Rejected("GAME_FINISHED", "The game is already finished"))
    }

    /** Скрытой информации нет: маскируются только чужие варианты ходов; свёртка маскированных = маска полной свёртки. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("viewers")
    fun `fold of masked events equals masked full fold`(viewer: ViewerKey) {
        val journal = engine.start(rules, listOf(ALICE, BOB), SEED, RUSSIAN, TimeControl(30, 0), NOW).toMutableList()
        val whiteId = idOf(fold(journal), WHITE)
        val blackId = idOf(fold(journal), BLACK)
        journal += moved(journal, whiteId, 3 to 3, 4 to 4)
        journal += moved(journal, blackId, 6 to 6, 5 to 5)
        journal += moved(journal, whiteId, 4 to 4, 6 to 6)
        journal += engine.timeout(rules, fold(journal), NOW)

        val ofMasked = SessionEngine.fold(null, journal.map { engine.mask(rules, it, viewer) })
        val full = fold(journal).tree
        @Suppress("UNCHECKED_CAST")
        val state = full.getValue("state") as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val players = (state.getValue("players") as List<Map<String, Any?>>)
            .map { if (viewer.sees(UUID.fromString(it["id"] as String))) it else it + ("moveOptions" to emptyList<Any>()) }

        assertThat(ofMasked).isEqualTo(full + ("state" to state + ("players" to players)))
        assertThat(fold(journal).session.status).isEqualTo(SessionStatus.FINISHED)
    }

    /**
     * Позиция поверх начального журнала варианта: шашки, счётчики, история позиций — с этой позицией; очередь — того, кто
     * ходит первым в варианте.
     */
    private fun position(
        pieces: List<CheckersPiece>,
        quietMoves: Int = 0,
        materialMoves: Int = 0,
        timeControl: TimeControl? = NO_TIMER,
        settings: CheckersSettings = RUSSIAN,
    ): List<SessionEvent> {
        val started = engine.start(rules, listOf(ALICE, BOB), SEED, settings, timeControl, NOW)
        val history = listOf(CheckersDraws.positionKey(CheckersMoveRules.of(settings.variant).firstColor, pieces))
        return started + listOf(
            SessionEvents.put("test-position", SessionEvents.path("state", "pieces"), pieces),
            SessionEvents.patch(
                "test-position",
                SessionEvents.path("state"),
                mapOf("quietMoves" to quietMoves, "materialMoves" to materialMoves, "positionHistory" to history),
            ),
        ).map { it.copy(value = codec.toTree(it.value)) }
    }

    private fun accepted(journal: List<SessionEvent>, player: UUID, vararg path: Pair<Int, Int>): List<SessionEvent> =
        accepted(journal, player, moveOf(*path))

    private fun accepted(journal: List<SessionEvent>, player: UUID, move: CheckersMove): List<SessionEvent> {
        val outcome = engine.move(rules, fold(journal), player, move, NOW)
        assertThat(outcome).isInstanceOf(MoveOutcome.Accepted::class.java)
        return (outcome as MoveOutcome.Accepted).events
    }

    private fun rejected(journal: List<SessionEvent>, player: UUID, move: CheckersMove): String {
        val outcome = engine.move(rules, fold(journal), player, move, NOW)
        assertThat(outcome).isInstanceOf(MoveOutcome.Rejected::class.java)
        return (outcome as MoveOutcome.Rejected).code
    }

    private fun moved(journal: List<SessionEvent>, player: UUID, vararg path: Pair<Int, Int>) =
        (engine.move(rules, fold(journal), player, moveOf(*path), NOW) as MoveOutcome.Accepted).events

    private fun rejection(journal: List<SessionEvent>, player: UUID, vararg path: Pair<Int, Int>): String {
        val outcome = engine.move(rules, fold(journal), player, moveOf(*path), NOW)
        assertThat(outcome).isInstanceOf(MoveOutcome.Rejected::class.java)
        return (outcome as MoveOutcome.Rejected).code
    }

    private fun fold(events: List<SessionEvent>): SessionDocument = engine.read(rules, SessionEngine.fold(null, events)!!)

    private fun game(events: List<SessionEvent>) = fold(events).session.state.game as CheckersGameState

    private fun idOf(document: SessionDocument, color: CheckersColor): UUID =
        document.session.state.players.single { (it.game as CheckersPlayer).color == color }.id

    companion object {
        private val ALICE = UUID.fromString("01990000-0000-7000-8000-00000000000a")
        private val BOB = UUID.fromString("01990000-0000-7000-8000-00000000000b")
        private val NOW = Instant.parse("2026-10-22T10:00:00Z")
        private const val SEED = 7L
        private val RUSSIAN = CheckersSettings(CheckersVariant.RUSSIAN)
        private val INTERNATIONAL = CheckersSettings(CheckersVariant.INTERNATIONAL)
        private val ENGLISH = CheckersSettings(CheckersVariant.ENGLISH)
        private val NO_TIMER: TimeControl? = null

        private fun w(id: String, x: Int, y: Int) = CheckersPiece(id, WHITE, CheckersPieceKind.MAN, listOf(x, y))
        private fun wk(id: String, x: Int, y: Int) = CheckersPiece(id, WHITE, CheckersPieceKind.KING, listOf(x, y))
        private fun b(id: String, x: Int, y: Int) = CheckersPiece(id, BLACK, CheckersPieceKind.MAN, listOf(x, y))
        private fun bk(id: String, x: Int, y: Int) = CheckersPiece(id, BLACK, CheckersPieceKind.KING, listOf(x, y))

        private fun squares(vararg path: Pair<Int, Int>) = path.map { listOf(it.first, it.second) }

        private fun moveOf(vararg path: Pair<Int, Int>) = CheckersMove("MOVE", CheckersMoveData(squares(*path)))

        /** Ход с предложением ничьей. */
        private fun offer(vararg path: Pair<Int, Int>) = CheckersMove("MOVE", CheckersMoveData(squares(*path), offerDraw = true))

        private fun option(type: String) = mapOf("type" to type, "data" to null)

        private fun key(name: String) = PathSegment.Key(name)

        private fun piecePath(id: String) = listOf(key("state"), key("pieces"), PathSegment.Id(id))

        private fun playerOptions(id: UUID) = listOf(key("state"), key("players"), PathSegment.Id(id.toString()), key("moveOptions"))

        @JvmStatic
        fun viewers(): Stream<ViewerKey> = Stream.of(ViewerKey.Full, ViewerKey.Public, ViewerKey.Player(ALICE), ViewerKey.Player(BOB))

        @JvmStatic
        fun endings(): Stream<Arguments> = Stream.of(
            arguments(
                "no pieces left",
                listOf(w("w1", 3, 3), b("b1", 4, 4)),
                0,
                listOf(3 to 3, 5 to 5),
                ResultOutcome.WIN,
                "NO_PIECES",
            ),
            arguments(
                "no moves left",
                listOf(w("w1", 1, 7), w("w2", 3, 7), w("w3", 3, 5), b("b1", 2, 8)),
                0,
                listOf(3 to 5, 4 to 6),
                ResultOutcome.WIN,
                "NO_MOVES",
            ),
            arguments(
                "30 quiet king moves - 15 of each side",
                listOf(wk("w1", 1, 1), bk("b1", 8, 2)),
                29,
                listOf(1 to 1, 2 to 2),
                ResultOutcome.DRAW,
                "DRAW_RULE",
            ),
        )

        /**
         * Правила окончаний: позиция без взятий, ход того, кто ходит первым, и порог счётчика `materialMoves` (с этим ходом).
         * «N-й ход сильнейшей стороны» — 2N − 1, «по N ходов» — 2N.
         */
        @JvmStatic
        fun endgames(): Stream<Arguments> = Stream.of(
            arguments(
                "russian - three kings against a lone king, 15th move of the stronger side",
                RUSSIAN,
                listOf(wk("w1", 1, 3), wk("w2", 3, 1), wk("w3", 1, 7), bk("b1", 8, 4)),
                listOf(1 to 7, 2 to 8),
                29,
            ),
            arguments(
                "russian - a lone king on the main road against three pieces, 5th move of the stronger side",
                RUSSIAN,
                listOf(wk("w1", 1, 3), wk("w2", 8, 2), wk("w3", 2, 8), bk("b1", 4, 4)),
                listOf(1 to 3, 2 to 4),
                9,
            ),
            arguments("russian - king against king, 5 moves each", RUSSIAN, listOf(wk("w1", 1, 1), bk("b1", 8, 2)), listOf(1 to 1, 2 to 2), 10),
            arguments(
                "russian - four pieces with kings on both sides, 30 moves each",
                RUSSIAN,
                listOf(wk("w1", 1, 1), w("w2", 5, 1), bk("b1", 8, 2), b("b2", 2, 8)),
                listOf(1 to 1, 2 to 2),
                60,
            ),
            arguments(
                "international - three pieces with a king against a lone king, 16 moves each",
                INTERNATIONAL,
                listOf(wk("w1", 1, 1), w("w2", 3, 1), w("w3", 5, 1), bk("b1", 10, 2)),
                listOf(1 to 1, 2 to 2),
                32,
            ),
            arguments(
                "international - king against king, 5 moves each",
                INTERNATIONAL,
                listOf(wk("w1", 1, 1), bk("b1", 10, 2)),
                listOf(1 to 1, 2 to 2),
                10,
            ),
        )

        @JvmStatic
        fun drawLimits(): Stream<Arguments> = Stream.of(
            arguments(CheckersVariant.RUSSIAN, 30),
            arguments(CheckersVariant.INTERNATIONAL, 50),
            arguments(CheckersVariant.ENGLISH, 80),
        )

        @JvmStatic
        fun rejections(): Stream<Arguments> = Stream.of(
            arguments("not your turn", false, CheckersMove("MOVE", CheckersMoveData(squares(8 to 8, 7 to 7))), "NOT_YOUR_TURN"),
            arguments("unknown move", true, CheckersMove("JUMP"), "UNKNOWN_MOVE"),
            arguments("move without a path", true, CheckersMove("MOVE"), "ILLEGAL_MOVE"),
            arguments("square is not a pair", true, CheckersMove("MOVE", CheckersMoveData(listOf(listOf(3, 3, 1), listOf(4, 4)))), "ILLEGAL_MOVE"),
            arguments("move of the opponent piece", true, CheckersMove("MOVE", CheckersMoveData(squares(8 to 8, 7 to 7))), "ILLEGAL_MOVE"),
            arguments("move to an occupied or far square", true, CheckersMove("MOVE", CheckersMoveData(squares(3 to 3, 6 to 6))), "ILLEGAL_MOVE"),
        )
    }
}
