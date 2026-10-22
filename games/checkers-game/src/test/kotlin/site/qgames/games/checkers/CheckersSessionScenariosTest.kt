package site.qgames.games.checkers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.games.api.GameRules
import site.qgames.games.api.TimeControl
import site.qgames.games.api.TimeControlMode
import site.qgames.games.api.core.MoveOption
import site.qgames.games.api.result.ResultOutcome
import site.qgames.games.api.result.ResultState
import site.qgames.games.api.session.MoveOutcome
import site.qgames.games.api.session.PathSegment
import site.qgames.games.api.session.SessionDocument
import site.qgames.games.api.session.SessionEngine
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionEventMethod
import site.qgames.games.api.session.SessionEvents
import site.qgames.games.api.session.SessionStatus
import site.qgames.games.api.session.ViewerKey
import site.qgames.games.api.turns.TurnsState
import site.qgames.games.checkers.CheckersColor.BLACK
import site.qgames.games.checkers.CheckersColor.WHITE
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

/**
 * Партия шашек (русский вариант) на платформе: генерация и проверка ходов по флагам правил, события, окончания и
 * маскировка — чистые функции, без сервиса. Позиции задаются поверх начального журнала событиями-патчами.
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
                        "timeControl" to mapOf("mode" to "NONE", "moveSeconds" to null),
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
        assertThat(state.keys).containsExactlyInAnyOrder("players", "timer", "turns", "result", "pieces", "lastMove", "quietMoves")
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
    fun `only available variants pass the settings check`() {
        assertThat(checkers.validateSettings(CheckersSettings(CheckersVariant.RUSSIAN))).isEmpty()
        assertThat(checkers.validateSettings(CheckersSettings(CheckersVariant.INTERNATIONAL)).map { it.field }).containsExactly("variant")
        assertThat(checkers.validateSettings(CheckersSettings(CheckersVariant.ENGLISH)).map { it.field }).containsExactly("variant")
        assertThat(checkers.settingsLimits).isEqualTo(CheckersSettingsLimits(listOf(CheckersVariant.RUSSIAN)))
    }

    @Test
    fun `quiet move of a man produces piece, last move and turn events`() {
        val journal = position(listOf(w("w1", 3, 3), b("b1", 8, 8)))

        val events = accepted(journal, white, 3 to 3, 4 to 4)

        assertThat(events.map { Triple(it.code, it.method, it.path) }).containsExactly(
            Triple("piece-moved", SessionEventMethod.PATCH, piecePath("w1")),
            Triple("last-move-changed", SessionEventMethod.PUT, listOf(key("state"), key("lastMove"))),
            Triple("turn-changed", SessionEventMethod.PUT, listOf(key("state"), key("turns"))),
            Triple("move-options-changed", SessionEventMethod.PUT, playerOptions(ALICE)),
            Triple("move-options-changed", SessionEventMethod.PUT, playerOptions(BOB)),
        )
        assertThat(events[0].value).isEqualTo(mapOf("square" to listOf(4, 4)))
        assertThat(events[1].value).isEqualTo(mapOf("pieceId" to "w1", "path" to listOf(listOf(3, 3), listOf(4, 4)), "captured" to emptyList<Any>()))
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
        assertThat(events.single { it.code == "counters-changed" }.value).isEqualTo(mapOf("quietMoves" to 1))
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
        assertThat(manMove.single { it.code == "counters-changed" }.value).isEqualTo(mapOf("quietMoves" to 0))
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
        val journal = position(listOf(w("w1", 3, 3), b("b1", 8, 8)), timeControl = TimeControl(TimeControlMode.PER_MOVE, 30))

        val events = (engine.move(rules, fold(journal), black, CheckersMove("RESIGN"), NOW) as MoveOutcome.Accepted).events

        assertThat(events.first().code).isEqualTo("player-resigned")
        assertThat(events.first().method).isEqualTo(SessionEventMethod.INFO)
        assertThat(events.first().value).isEqualTo(mapOf("playerId" to black.toString()))
        assertThat(events.map { it.code }.takeLast(2)).containsExactly("timer-stopped", "session-finished")
        assertThat(fold(journal + events).session.state.result).isEqualTo(ResultState(ResultOutcome.WIN, listOf(white), "RESIGN"))
    }

    @Test
    fun `timeout loses the game`() {
        val journal = position(listOf(w("w1", 3, 3), b("b1", 8, 8)), timeControl = TimeControl(TimeControlMode.PER_MOVE, 10))

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
        val state = finished.state as site.qgames.games.api.session.SessionState<CheckersGameState>

        val decision = checkers.decide(state, RUSSIAN, white, CheckersMove("RESIGN"))

        assertThat(decision).isEqualTo(site.qgames.games.api.Decision.Rejected("GAME_FINISHED", "The game is already finished"))
    }

    /** Скрытой информации нет: маскируются только чужие варианты ходов; свёртка маскированных = маска полной свёртки. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("viewers")
    fun `fold of masked events equals masked full fold`(viewer: ViewerKey) {
        val journal = engine.start(rules, listOf(ALICE, BOB), SEED, RUSSIAN, TimeControl(TimeControlMode.PER_MOVE, 30), NOW).toMutableList()
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

    /** Позиция поверх начального журнала: шашки, счётчик и очередь белых. */
    private fun position(
        pieces: List<CheckersPiece>,
        quietMoves: Int = 0,
        timeControl: TimeControl = NO_TIMER,
    ): List<SessionEvent> {
        val started = engine.start(rules, listOf(ALICE, BOB), SEED, RUSSIAN, timeControl, NOW)
        return started + listOf(
            SessionEvents.put("test-position", SessionEvents.path("state", "pieces"), pieces),
            SessionEvents.patch("test-position", SessionEvents.path("state"), mapOf("quietMoves" to quietMoves)),
        ).map { it.copy(value = codec.toTree(it.value)) }
    }

    private fun accepted(journal: List<SessionEvent>, player: UUID, vararg path: Pair<Int, Int>): List<SessionEvent> {
        val outcome = engine.move(rules, fold(journal), player, moveOf(*path), NOW)
        assertThat(outcome).isInstanceOf(MoveOutcome.Accepted::class.java)
        return (outcome as MoveOutcome.Accepted).events
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
        private val NO_TIMER = TimeControl(TimeControlMode.NONE, null)

        private fun w(id: String, x: Int, y: Int) = CheckersPiece(id, WHITE, CheckersPieceKind.MAN, listOf(x, y))
        private fun wk(id: String, x: Int, y: Int) = CheckersPiece(id, WHITE, CheckersPieceKind.KING, listOf(x, y))
        private fun b(id: String, x: Int, y: Int) = CheckersPiece(id, BLACK, CheckersPieceKind.MAN, listOf(x, y))
        private fun bk(id: String, x: Int, y: Int) = CheckersPiece(id, BLACK, CheckersPieceKind.KING, listOf(x, y))

        private fun squares(vararg path: Pair<Int, Int>) = path.map { listOf(it.first, it.second) }

        private fun moveOf(vararg path: Pair<Int, Int>) = CheckersMove("MOVE", CheckersMoveData(squares(*path)))

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
