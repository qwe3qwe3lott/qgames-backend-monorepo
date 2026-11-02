package site.qgames.games.croissant

import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import site.qgames.common.docpatch.PatchMethod
import site.qgames.games.core.Decision
import site.qgames.games.core.GameRules
import site.qgames.games.core.MoveOutcome
import site.qgames.games.core.PlayerState
import site.qgames.games.core.Result
import site.qgames.games.core.ResultOutcome
import site.qgames.games.core.ResultState
import site.qgames.games.core.SessionEvents
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
    fun `the catalog rejects tiles with two elements on a side or trails without a target`() {
        val n = CroissantDirection.N
        val s = CroissantDirection.S
        val e = CroissantDirection.E
        val w = CroissantDirection.W
        val allPastures = listOf(
            CroissantPastureSpec(CroissantDirection.entries.flatMap { listOf(CroissantHalfEdge(it, 0), CroissantHalfEdge(it, 1)) }),
        )
        fun type(
            trails: List<CroissantTrailSpec> = emptyList(),
            bakeries: List<CroissantBakerySpec> = emptyList(),
            pastures: List<CroissantPastureSpec> = allPastures,
            dairy: Boolean = false,
            garden: Boolean = false,
        ) = CroissantTileType("test", "Тест", List(4) { CroissantEdge.PASTURE }, bakeries, trails, dairy, garden, pastures)

        assertThatThrownBy { type(trails = listOf(CroissantTrailSpec(listOf(n, s)), CroissantTrailSpec(listOf(n, e)))) }
            .hasMessageContaining("two trails")
        assertThatThrownBy { type(trails = listOf(CroissantTrailSpec(listOf(n)))) }
            .hasMessageContaining("exactly one target")
        assertThatThrownBy { type(trails = listOf(CroissantTrailSpec(listOf(n), dairy = true, garden = true)), dairy = true) }
            .hasMessageContaining("exactly one target")
        assertThatThrownBy { type(trails = listOf(CroissantTrailSpec(listOf(n, s), garden = true)), garden = true) }
            .hasMessageContaining("can't have a target")
        assertThatThrownBy { type(trails = listOf(CroissantTrailSpec(listOf(w), garden = true))) }
            .hasMessageContaining("missing garden")
        assertThatThrownBy { type(trails = listOf(CroissantTrailSpec(listOf(w), bakery = 0))) }
            .hasMessageContaining("missing bakery")
        assertThatThrownBy { type(dairy = true, garden = true) }.hasMessageContaining("center")
        assertThatThrownBy { type(bakeries = listOf(CroissantBakerySpec(listOf(n)))) }
            .hasMessageContaining("exactly once")
        assertThatThrownBy {
            type(bakeries = listOf(CroissantBakerySpec(listOf(n))), trails = listOf(CroissantTrailSpec(listOf(n, s))))
        }.hasMessageContaining("both a bakery and a trail")
        // Две независимые тропы W-N и S-E и раздельные пекарни на соседних сторонах — допустимые тайлы.
        type(trails = listOf(CroissantTrailSpec(listOf(w, n)), CroissantTrailSpec(listOf(s, e))))
        type(
            bakeries = listOf(CroissantBakerySpec(listOf(n)), CroissantBakerySpec(listOf(e))),
            trails = listOf(CroissantTrailSpec(listOf(w), bakery = 0), CroissantTrailSpec(listOf(s), bakery = 1)),
            pastures = listOf(
                CroissantPastureSpec(listOf(CroissantHalfEdge(w, 0)), bakeries = listOf(0)),
                CroissantPastureSpec(listOf(CroissantHalfEdge(w, 1), CroissantHalfEdge(s, 0)), bakeries = listOf(0, 1)),
                CroissantPastureSpec(listOf(CroissantHalfEdge(s, 1)), bakeries = listOf(1)),
            ),
        )
    }

    @Test
    fun `branches of a fork end at the garden and are separate trails`() {
        // Развилка: тропы N, E и S до сада. Ворота пекарни сверху замыкают северную ветку: пекарня — с одного конца,
        // сад — с другого.
        val fork = tile(0, 0, CroissantCatalog.TRAIL_CROSS, 0)
        val gate = tile(0, 1, CroissantCatalog.BAKERY_TRAIL, 0)
        val trails = rebuild(listOf(fork, gate)).filter { it.type == CroissantSpotType.TRAIL }

        assertThat(trails).hasSize(3)
        val north = trails.single { spot -> spot.members.any { it.tileId == gate.id } }
        assertThat(north.members).hasSize(2)
        assertThat(north.complete).isTrue()
        assertThat(CroissantScoring.completed(north)).isEqualTo(2)
        assertThat(trails - north).allSatisfy {
            assertThat(it.members).hasSize(1)
            assertThat(it.open).isEqualTo(1)
        }
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

        val placed = rules.decide(state, settings, 1L, ALICE, place(0, 1, 180)) as Decision.Accepted

        // Выкладка только завершает участок: крошки — в конце хода, после шага кондитера.
        assertThat(placed.events.map { it.code })
            .contains(CroissantGameRules.TILE_PLACED, CroissantGameRules.SPOT_UPDATED, CroissantGameRules.TILE_PENDING)
            .doesNotContain(CroissantGameRules.SPOT_COMPLETED, CroissantGameRules.CRUMBS_CHANGED)
        val merged = placed.events.filter { it.code == CroissantGameRules.SPOT_UPDATED }
            .map { it.value as CroissantSpot }
            .filter { it.type == CroissantSpotType.BAKERY }
        assertThat(merged).hasSize(1)
        assertThat(merged.single().complete).isTrue()
        assertThat(merged.single().open).isZero()
        assertThat(merged.single().members).hasSize(2)
        assertThat(merged.single().confectioners).hasSize(1)
        // Занятая пекарня кондитеру недоступна, свободный луг нового тайла — доступен.
        val pending = placed.events.single { it.code == CroissantGameRules.TILE_PENDING }.value as CroissantPending
        assertThat(pending.spots).doesNotContain(bakery.id)
        assertThat(pending.completed).containsExactly(bakery.id)

        val skipped = rules.decide(
            waitingAfter(state, placed),
            settings,
            1L,
            ALICE,
            CroissantMove(CroissantGameRules.SKIP_CONFECTIONER),
        ) as Decision.Accepted

        assertThat(skipped.events.map { it.code }).contains(
            CroissantGameRules.SPOT_COMPLETED,
            CroissantGameRules.CRUMBS_CHANGED,
            CroissantGameRules.CONFECTIONERS_RETURNED,
        )
        val crumbs = skipped.events.filter { it.code == CroissantGameRules.CRUMBS_CHANGED }
        assertThat(crumbs).hasSize(1)
        assertThat(crumbs.single().method).isEqualTo(PatchMethod.PATCH)
        assertThat(crumbs.single().value).isEqualTo(mapOf("crumbs" to 4))
        assertThat(crumbs.single().path).hasSize(3)
        val settled = skipped.events.filter { it.code == CroissantGameRules.SPOT_UPDATED }
            .map { it.value as CroissantSpot }
            .single { it.id == bakery.id }
        assertThat(settled.confectioners).isEmpty()
    }

    @Test
    fun `a confectioner on a bakery the tile has just closed scores at once and returns`() {
        val board = listOf(tile(0, 0, CroissantCatalog.BAKERY_ONE, 0))
        val state = sessionState(board = board, currentTile = CroissantCatalog.BAKERY_ONE.code, spots = rebuild(board))

        val placed = rules.decide(state, settings, 1L, ALICE, place(0, 1, 180)) as Decision.Accepted
        val pending = placed.events.single { it.code == CroissantGameRules.TILE_PENDING }.value as CroissantPending
        val bakery = pending.completed.single()
        assertThat(pending.spots).contains(bakery)

        val assigned = rules.decide(
            waitingAfter(state, placed),
            settings,
            1L,
            ALICE,
            CroissantMove(CroissantGameRules.PLACE_CONFECTIONER, CroissantMoveData(spotId = bakery)),
        ) as Decision.Accepted

        val codes = assigned.events.map { it.code }
        assertThat(codes).containsSubsequence(
            CroissantGameRules.CONFECTIONER_PLACED,
            CroissantGameRules.SPOT_COMPLETED,
            CroissantGameRules.CRUMBS_CHANGED,
            CroissantGameRules.CONFECTIONERS_RETURNED,
            CroissantGameRules.TILE_DRAWN,
        )
        assertThat(assigned.events.single { it.code == CroissantGameRules.CRUMBS_CHANGED }.value)
            .isEqualTo(mapOf("crumbs" to 4))
        assertThat(assigned.events.single { it.code == CroissantGameRules.CONFECTIONERS_RETURNED }.value)
            .isEqualTo(mapOf("confectioners" to 7))
    }

    @Test
    fun `a closed bakery without confectioners scores nobody after the step is skipped`() {
        val board = listOf(tile(0, 0, CroissantCatalog.BAKERY_ONE, 0))
        val state = sessionState(board = board, currentTile = CroissantCatalog.BAKERY_ONE.code, spots = rebuild(board))

        val placed = rules.decide(state, settings, 1L, ALICE, place(0, 1, 180)) as Decision.Accepted
        val skipped = rules.decide(
            waitingAfter(state, placed),
            settings,
            1L,
            ALICE,
            CroissantMove(CroissantGameRules.SKIP_CONFECTIONER),
        ) as Decision.Accepted

        assertThat(skipped.events.map { it.code })
            .contains(CroissantGameRules.SPOT_COMPLETED)
            .doesNotContain(CroissantGameRules.CRUMBS_CHANGED, CroissantGameRules.CONFECTIONERS_RETURNED)
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
    fun `a timed out player of two drops out and the other wins even with fewer crumbs`() {
        val board = listOf(tile(0, 0, CroissantCatalog.BAKERY_TRAIL, 0))
        val state = sessionState(
            board = board,
            currentTile = CroissantCatalog.DAIRY.code,
            spots = rebuild(board),
            alice = CroissantPlayer(crumbs = 10, confectioners = 7),
        )

        val events = rules.onTimeout(state, settings, seed = 5L)

        assertThat(events.map { it.code }).containsSubsequence(
            CroissantGameRules.MOVE_TIMED_OUT,
            CroissantGameRules.PLAYER_OUT,
            CroissantGameRules.TILE_RETURNED,
            CroissantGameRules.GAME_SCORED,
            Result.RESULT_SET,
            "session-finished",
        )
        assertThat(events.map { it.code }).doesNotContain(CroissantGameRules.TILE_PLACED, "turn-changed")
        assertThat(events.single { it.code == CroissantGameRules.PLAYER_OUT }.value).isEqualTo(mapOf("out" to true))
        assertThat(events.single { it.code == Result.RESULT_SET }.value)
            .isEqualTo(ResultState(ResultOutcome.WIN, listOf(BOB), CroissantGameRules.RESULT_TIMEOUT))
    }

    @Test
    fun `a timed out player of three drops out and the turn goes on`() {
        val board = listOf(tile(0, 0, CroissantCatalog.BAKERY_TRAIL, 0))
        val state = sessionState(
            board = board,
            currentTile = CroissantCatalog.DAIRY.code,
            spots = rebuild(board),
            carol = CroissantPlayer(crumbs = 0, confectioners = 7),
        )

        val events = rules.onTimeout(state, settings, seed = 5L)

        assertThat(events.map { it.code })
            .containsSubsequence(
                CroissantGameRules.PLAYER_OUT,
                CroissantGameRules.TILE_RETURNED,
                CroissantGameRules.TILE_DRAWN,
                "turn-changed",
            )
            .doesNotContain("session-finished", CroissantGameRules.TILE_DISCARDED)
        val turns = events.single { it.code == "turn-changed" }.value as TurnsState
        assertThat(turns.currentPlayerId).isEqualTo(BOB)
        // Тайл вернулся в колоду: размер +1, момент возврата записан, следующий тайл — следующий по колоде.
        assertThat(events.single { it.code == CroissantGameRules.TILE_RETURNED }.value)
            .isEqualTo(mapOf("deckReturns" to listOf(1), "deckSize" to 72))
        val deck = CroissantCatalog.deck(5L, listOf(1))
        @Suppress("UNCHECKED_CAST")
        val drawn = events.first { it.code == CroissantGameRules.TILE_DRAWN }.value as Map<String, Any?>
        assertThat(drawn["deckPos"] as Int).isGreaterThan(1)
        assertThat(drawn["currentTile"]).isEqualTo(deck[(drawn["deckPos"] as Int) - 1])
    }

    @Test
    fun `a returned tile goes back into the rest of the deck by the seed`() {
        val plain = CroissantCatalog.deck(7L)
        val returned = CroissantCatalog.deck(7L, listOf(10))

        assertThat(CroissantCatalog.deck(7L, listOf(10))).isEqualTo(returned)
        assertThat(returned).hasSize(plain.size + 1)
        // Уже открытая часть колоды не меняется, тайл с позиции 10 появляется ещё раз дальше.
        assertThat(returned.take(10)).isEqualTo(plain.take(10))
        // Без вставленной копии остаток колоды — тот же, что без возврата.
        val rest = returned.drop(10)
        assertThat(rest.indices.any { i -> rest[i] == plain[9] && rest.filterIndexed { j, _ -> j != i } == plain.drop(10) })
            .isTrue()
        assertThat(CroissantCatalog.deck(7L, listOf(10, 10))).hasSize(plain.size + 2)
    }

    @Test
    fun `the turn order skips players who are out`() {
        val board = listOf(tile(0, 0, CroissantCatalog.DAIRY, 0))
        // Кэрол ходит последней в раунде, Алиса выбыла: очередь переходит сразу к Бобу.
        val state = sessionState(
            board = board,
            currentTile = CroissantCatalog.DAIRY.code,
            spots = rebuild(board),
            alice = CroissantPlayer(crumbs = 0, confectioners = 7, out = true),
            carol = CroissantPlayer(crumbs = 0, confectioners = 0),
            current = CAROL,
        )

        val placed = rules.decide(state, settings, 5L, CAROL, place(1, 0, 0)) as Decision.Accepted

        val turns = placed.events.single { it.code == "turn-changed" }.value as TurnsState
        assertThat(turns.currentPlayerId).isEqualTo(BOB)
        assertThat(turns.round).isEqualTo(2)
    }

    @Test
    fun `a timeout on the confectioner step settles what the tile has closed`() {
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
            carol = CroissantPlayer(crumbs = 0, confectioners = 7),
        )
        val placed = rules.decide(state, settings, 1L, ALICE, place(0, 1, 180)) as Decision.Accepted

        val events = rules.onTimeout(waitingAfter(state, placed), settings, seed = 1L)

        assertThat(events.map { it.code }).containsSubsequence(
            CroissantGameRules.MOVE_TIMED_OUT,
            CroissantGameRules.PLAYER_OUT,
            CroissantGameRules.CONFECTIONER_SKIPPED,
            CroissantGameRules.SPOT_COMPLETED,
            CroissantGameRules.CRUMBS_CHANGED,
            CroissantGameRules.TILE_PENDING_CLEARED,
            "turn-changed",
        )
        // Выбывший получает крошки за свой участок, как все.
        val crumbs = events.single { it.code == CroissantGameRules.CRUMBS_CHANGED }
        assertThat(crumbs.path).contains(SessionEvents.id(ALICE))
        assertThat(crumbs.value).isEqualTo(mapOf("crumbs" to 4))
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

    /**
     * Состояние на шаге кондитера после выкладки [placed] в [state]: тайл на поле, участки пересобраны так же, как в
     * правилах (кондитеры переезжают с прежних участков), ожидание — из события.
     */
    private fun waitingAfter(state: SessionState<Any>, placed: Decision.Accepted): SessionState<Any> {
        val game = state.game as CroissantGameState
        val tile = placed.events.single { it.code == CroissantGameRules.TILE_PLACED }.value as CroissantPlacedTile
        val board = game.board + tile
        val pending = placed.events.single { it.code == CroissantGameRules.TILE_PENDING }.value as CroissantPending
        val spots = CroissantNetwork.rebuild(CroissantLayout(board), board, game.spots)
        return state.copy(game = game.copy(board = board, spots = spots, pending = pending))
    }

    private fun rebuild(board: List<CroissantPlacedTile>): List<CroissantSpot> =
        CroissantNetwork.rebuild(CroissantLayout(board), board, emptyList())

    private fun sessionState(
        board: List<CroissantPlacedTile>,
        currentTile: String?,
        spots: List<CroissantSpot>,
        pending: CroissantPending? = null,
        alice: CroissantPlayer = CroissantPlayer(crumbs = 0, confectioners = 7),
        bob: CroissantPlayer = CroissantPlayer(crumbs = 0, confectioners = 7),
        carol: CroissantPlayer? = null,
        current: UUID = ALICE,
    ): SessionState<Any> = SessionState(
        stage = Stages.PLAY,
        players = listOfNotNull(
            PlayerState(ALICE, emptyList(), alice),
            PlayerState(BOB, emptyList(), bob),
            carol?.let { PlayerState(CAROL, emptyList(), it) },
        ),
        timer = null,
        turns = TurnsState(current, round = 1, rounds = null),
        result = null,
        game = CroissantGameState(
            board = board,
            spots = spots,
            pending = pending,
            currentTile = currentTile,
            deckPos = 1,
            deckSize = 71,
            deckReturns = emptyList(),
        ),
    )

    private companion object {
        val ALICE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000a")
        val BOB: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000b")
        val CAROL: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000c")
    }
}
