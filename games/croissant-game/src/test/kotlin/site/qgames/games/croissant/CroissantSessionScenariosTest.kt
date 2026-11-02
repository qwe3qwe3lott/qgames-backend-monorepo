package site.qgames.games.croissant

import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import site.qgames.common.docpatch.PatchMethod
import site.qgames.games.core.Decision
import site.qgames.games.core.GameRules
import site.qgames.games.core.MoveOutcome
import site.qgames.games.core.PlayerState
import site.qgames.games.core.ResultOutcome
import site.qgames.games.core.SessionDocument
import site.qgames.games.core.SessionEngine
import site.qgames.games.core.SessionState
import site.qgames.games.core.SessionStatus
import site.qgames.games.core.Stages
import site.qgames.games.core.TimeBonusType
import site.qgames.games.core.TimeControlSettings
import site.qgames.games.core.TurnsState

/**
 * Партия «Круассана» на платформе: каталог и колода, стыковка рёбер, подсчёт «Золотых крошек» при завершении участка и
 * вся партия до итога. Правила — чистые функции, проверяются без Spring и сервиса.
 */
class CroissantSessionScenariosTest {

    @Suppress("UNCHECKED_CAST")
    private val rules = CroissantGameRules() as GameRules<Any, Any, Any, Any>

    private val engine = SessionEngine(JsonSessionCodec())
    private val now = Instant.parse("2026-11-02T10:00:00Z")
    private val settings = CroissantAdvancedSettings(
        confectioners = 7,
        timeControl = TimeControlSettings(false, 1800, TimeBonusType.INCREMENT, 15),
    )

    @Test
    fun `catalog has 72 tiles, the deck 71 without the start tile`() {
        assertThat(CroissantCatalog.COUNTS.values.sum()).isEqualTo(72)
        assertThat(CroissantCatalog.COUNTS).containsKey(CroissantCatalog.START_TILE)
        val deck = CroissantCatalog.deck(seed = 7L)
        assertThat(deck).hasSize(71)
        assertThat(CroissantCatalog.TYPES.map { it.code }).containsExactlyInAnyOrderElementsOf(CroissantCatalog.COUNTS.keys)
    }

    @Test
    fun `deck order depends on the seed only`() {
        assertThat(CroissantCatalog.deck(1L)).isEqualTo(CroissantCatalog.deck(1L))
        assertThat(CroissantCatalog.deck(1L)).isNotEqualTo(CroissantCatalog.deck(2L))
    }

    @Test
    fun `rotation turns the sides of a tile`() {
        val tile = CroissantCatalog.BAKERY_ONE
        assertThat(CroissantShape(tile, 0).edge(CroissantDirection.N)).isEqualTo(CroissantEdge.BAKERY)
        assertThat(CroissantShape(tile, 90).edge(CroissantDirection.E)).isEqualTo(CroissantEdge.BAKERY)
        assertThat(CroissantShape(tile, 180).edge(CroissantDirection.S)).isEqualTo(CroissantEdge.BAKERY)
        assertThat(CroissantShape(tile, 270).edge(CroissantDirection.W)).isEqualTo(CroissantEdge.BAKERY)
        assertThat(CroissantShape(tile, 180).edge(CroissantDirection.N)).isEqualTo(CroissantEdge.PASTURE)
    }

    @Test
    fun `pasture half-edges meet across the cell border`() {
        // Прямая тропа: полуребра нижнего тайла через границу совпадают с полуребрами верхнего.
        val lower = CroissantShape(CroissantCatalog.TRAIL_STRAIGHT, 0).pastureHalves(0)
        val upperShape = CroissantShape(CroissantCatalog.TRAIL_STRAIGHT, 0)
        val upper = (0 until upperShape.pastureCount()).flatMap { upperShape.pastureHalves(it) }
        val facing = lower.map { CroissantHalfEdge(it.dir.opposite, it.half) }

        assertThat(upper).containsAll(facing)
    }

    @Test
    fun `start builds the board, spots and a drawn tile`() {
        val document = startDocument(seed = 11L)
        val session = engine.read(rules, document).session
        val game = session.state.game as CroissantGameState

        assertThat(session.status).isEqualTo(SessionStatus.IN_PROGRESS)
        assertThat(session.state.stage).isEqualTo(Stages.PLAY)
        assertThat(game.board).hasSize(1)
        assertThat(game.board.single().id).isEqualTo(CroissantGameRules.CELL_ORIGIN)
        assertThat(game.currentTile).isNotNull()
        assertThat(game.deckSize).isEqualTo(71)
        assertThat(game.deckPos).isEqualTo(1)
        assertThat(game.spots.map { it.type }).contains(
            CroissantSpotType.BAKERY,
            CroissantSpotType.TRAIL,
            CroissantSpotType.PASTURE,
        )
    }

    @Test
    fun `a tile closing a bakery pays crumbs to the confectioner owner`() {
        val first = tile(0, 0, CroissantCatalog.BAKERY_ONE, 0)
        val board = listOf(first)
        val base = rebuild(board)
        val bakery = base.single { it.type == CroissantSpotType.BAKERY }
        val spots = base.map {
            if (it.id == bakery.id) it.copy(confectioners = listOf(CroissantConfectioner(ALICE, first.id))) else it
        }
        val state = sessionState(
            board = board,
            currentTile = CroissantCatalog.BAKERY_ONE.code,
            spots = spots,
            alice = CroissantPlayer(crumbs = 0, confectioners = 6),
        )

        val decision = rules.decide(state, settings, 1L, ALICE, place(0, 1, 180))
        assertThat(decision).isInstanceOf(Decision.Accepted::class.java)
        val events = (decision as Decision.Accepted).events

        assertThat(events.map { it.code }).contains(
            CroissantGameRules.TILE_PLACED,
            CroissantGameRules.SPOT_UPDATED,
            CroissantGameRules.SPOT_COMPLETED,
            CroissantGameRules.CRUMBS_CHANGED,
            CroissantGameRules.CONFECTIONERS_RETURNED,
        )
        val merged = events.filter { it.code == CroissantGameRules.SPOT_UPDATED }
            .map { it.value as CroissantSpot }
            .filter { it.type == CroissantSpotType.BAKERY }
        assertThat(merged).hasSize(1)
        assertThat(merged.single().complete).isTrue()
        assertThat(merged.single().open).isZero()
        assertThat(merged.single().members).hasSize(2)
        assertThat(merged.single().confectioners).isEmpty()

        val crumbs = events.filter { it.code == CroissantGameRules.CRUMBS_CHANGED }
        assertThat(crumbs).hasSize(1)
        assertThat(crumbs.single().method).isEqualTo(PatchMethod.PATCH)
        assertThat(crumbs.single().value).isEqualTo(mapOf("crumbs" to 4))
        assertThat(crumbs.single().path).hasSize(3)
    }

    @Test
    fun `a tile that does not match the border is rejected`() {
        val board = listOf(tile(0, 0, CroissantCatalog.BAKERY_ONE, 0))
        val state = sessionState(board = board, currentTile = CroissantCatalog.BAKERY_ONE.code, spots = rebuild(board))

        val decision = rules.decide(state, settings, 1L, ALICE, place(0, 1, 0))

        assertThat(decision).isEqualTo(
            Decision.Rejected(CroissantGameRules.ERR_ILLEGAL_PLACEMENT, "The tile does not fit here"),
        )
    }

    @Test
    fun `a placed tile waits for a confectioner and then passes the turn`() {
        val board = listOf(tile(0, 0, CroissantCatalog.DAIRY, 0))
        val state = sessionState(
            board = board,
            currentTile = CroissantCatalog.BAKERY_ONE.code,
            spots = rebuild(board),
        )

        val placed = rules.decide(state, settings, 3L, ALICE, place(0, 1, 0)) as Decision.Accepted
        val pendingEvent = placed.events.single { it.code == CroissantGameRules.TILE_PENDING }
        val pending = pendingEvent.value as CroissantPending
        assertThat(pending.spots).isNotEmpty()

        val waiting = sessionState(
            board = board + tile(0, 1, CroissantCatalog.BAKERY_ONE, 0),
            currentTile = null,
            spots = pending.spots.map { id ->
                placed.events.filter { it.code == CroissantGameRules.SPOT_UPDATED }
                    .map { it.value as CroissantSpot }
                    .first { it.id == id }
            },
            pending = pending,
        )

        val assigned = rules.decide(
            waiting,
            settings,
            3L,
            ALICE,
            CroissantMove(CroissantGameRules.PLACE_CONFECTIONER, CroissantMoveData(spotId = pending.spots.first())),
        ) as Decision.Accepted

        assertThat(assigned.events.map { it.code }).contains(
            CroissantGameRules.CONFECTIONER_PLACED,
            CroissantGameRules.TILE_PENDING_CLEARED,
            CroissantGameRules.TILE_DRAWN,
            "turn-changed",
        )
        val placedSpot = assigned.events.first { it.code == CroissantGameRules.CONFECTIONER_PLACED }.value as CroissantSpot
        assertThat(placedSpot.confectioners.single().playerId).isEqualTo(ALICE)
    }

    @Test
    fun `another players move is rejected`() {
        val board = listOf(tile(0, 0, CroissantCatalog.BAKERY_TRAIL, 0))
        val state = sessionState(board = board, currentTile = CroissantCatalog.DAIRY.code, spots = rebuild(board))

        val decision = rules.decide(state, settings, 3L, BOB, place(0, 1, 0))

        assertThat(decision).isInstanceOf(Decision.Rejected::class.java)
        assertThat((decision as Decision.Rejected).code).isEqualTo(CroissantGameRules.ERR_NOT_YOUR_TURN)
    }

    @Test
    fun `timeout places a tile itself and never stalls the game`() {
        val board = listOf(tile(0, 0, CroissantCatalog.BAKERY_TRAIL, 0))
        val state = sessionState(board = board, currentTile = CroissantCatalog.DAIRY.code, spots = rebuild(board))

        val events = rules.onTimeout(state, settings, seed = 5L)

        assertThat(events.map { it.code }).contains(CroissantGameRules.MOVE_TIMED_OUT, CroissantGameRules.TILE_PLACED)
        assertThat(events.map { it.code }).anyMatch {
            it == CroissantGameRules.TILE_PENDING || it == "turn-changed" || it == "session-finished"
        }
    }

    @Test
    fun `a whole session is played to the final scoring`() {
        val document = playFullGame(seed = 2026L)

        val session = engine.read(rules, document).session
        assertThat(session.status).isEqualTo(SessionStatus.FINISHED)
        assertThat(session.state.result).isNotNull()
        val result = session.state.result!!
        assertThat(result.reason).isEqualTo(CroissantGameRules.RESULT_SCORED)
        if (result.outcome == ResultOutcome.WIN) {
            assertThat(result.winnerIds).hasSize(1)
        } else {
            assertThat(result.winnerIds).isEmpty()
        }
        val game = session.state.game as CroissantGameState
        assertThat(game.board.size).isBetween(2, 72)
        assertThat(game.currentTile).isNull()
    }

    private fun playFullGame(seed: Long): Map<String, Any?> {
        var document = startDocument(seed)
        var steps = 0
        while (steps < 1000) {
            val current = engine.read(rules, document).session
            if (current.status != SessionStatus.IN_PROGRESS) return document
            val playerId = current.state.turns.currentPlayerId ?: return document
            val options = rules.moveOptions(current.state, playerId)
            val move = moveFrom(options) ?: return document
            val outcome = engine.move(rules, engine.read(rules, document), playerId, move, seed, now)
            val accepted = outcome as? MoveOutcome.Accepted ?: error("Move rejected: $outcome")
            document = SessionEngine.fold(document, accepted.events)!!
            steps += 1
        }
        error("The session did not finish in $steps steps")
    }

    private fun moveFrom(options: List<site.qgames.games.core.MoveOption>): CroissantMove? {
        options.firstOrNull { it.type == CroissantGameRules.PLACE_TILE }?.let { option ->
            @Suppress("UNCHECKED_CAST")
            val placements = option.data!!["placements"] as List<Map<String, Any?>>
            val placement = placements.first()
            @Suppress("UNCHECKED_CAST")
            val rotations = placement["rotations"] as List<Int>
            return CroissantMove(
                CroissantGameRules.PLACE_TILE,
                CroissantMoveData(
                    x = placement["x"] as Int,
                    y = placement["y"] as Int,
                    rotation = rotations.first(),
                ),
            )
        }
        return options.firstOrNull { it.type == CroissantGameRules.SKIP_CONFECTIONER }
            ?.let { CroissantMove(CroissantGameRules.SKIP_CONFECTIONER) }
    }

    private fun startDocument(seed: Long): Map<String, Any?> {
        val events = engine.start(rules, listOf(ALICE, BOB), seed, settings, now)
        return SessionEngine.fold(null, events)!!
    }

    private fun place(x: Int, y: Int, rotation: Int) =
        CroissantMove(CroissantGameRules.PLACE_TILE, CroissantMoveData(x = x, y = y, rotation = rotation))

    private fun tile(x: Int, y: Int, type: CroissantTileType, rotation: Int) =
        CroissantPlacedTile("$x:$y", x, y, type.code, rotation)

    private fun rebuild(board: List<CroissantPlacedTile>): List<CroissantSpot> =
        CroissantNetwork.rebuild(CroissantLayout(board, CroissantCatalog.TYPES), board, emptyList())

    private fun sessionState(
        board: List<CroissantPlacedTile>,
        currentTile: String?,
        spots: List<CroissantSpot>,
        pending: CroissantPending? = null,
        alice: CroissantPlayer = CroissantPlayer(crumbs = 0, confectioners = 7),
        bob: CroissantPlayer = CroissantPlayer(crumbs = 0, confectioners = 7),
    ): SessionState<Any> = SessionState(
        stage = Stages.PLAY,
        players = listOf(
            PlayerState(ALICE, emptyList(), alice),
            PlayerState(BOB, emptyList(), bob),
        ),
        timer = null,
        turns = TurnsState(ALICE, round = 1, rounds = null),
        result = null,
        game = CroissantGameState(
            board = board,
            spots = spots,
            tiles = CroissantCatalog.TYPES,
            pending = pending,
            currentTile = currentTile,
            deckPos = 1,
            deckSize = 71,
        ),
    )

    private companion object {
        val ALICE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000a")
        val BOB: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000b")
    }
}
