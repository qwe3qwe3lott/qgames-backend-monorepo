package site.qgames.games.nothing

import java.time.Instant
import java.util.UUID
import java.util.stream.Stream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.games.core.Decision
import site.qgames.games.core.GameRules
import site.qgames.games.core.MoveOutcome
import site.qgames.common.docpatch.PathSegment
import site.qgames.games.core.PlayerClock
import site.qgames.games.core.Result
import site.qgames.games.core.ResultOutcome
import site.qgames.games.core.ResultState
import site.qgames.games.core.SessionDocument
import site.qgames.games.core.SessionEngine
import site.qgames.games.core.SessionEvent
import site.qgames.common.docpatch.PatchMethod
import site.qgames.games.core.SessionEvents
import site.qgames.games.core.SessionState
import site.qgames.games.core.SessionStatus
import site.qgames.games.core.TimeControl
import site.qgames.games.core.TimeControlSettings
import site.qgames.games.core.TimerOutcome
import site.qgames.games.core.TimerState
import site.qgames.games.core.TimerStatus
import site.qgames.games.core.TurnsState
import site.qgames.games.core.ViewerKey

/**
 * Партия «игры в ничто» на платформе (core: игроки, очерёдность, часы): события-патчи, их свёртка общим применителем и
 * маскировка — чистые функции, без сервиса.
 */
class NothingSessionScenariosTest {

    @Suppress("UNCHECKED_CAST")
    private val rules = NothingGameRules() as GameRules<Any, Any, Any, Any>

    private val engine = SessionEngine(JsonSessionCodec())

    @Test
    fun `start is one PUT of the whole document in the shape of the snapshot`() {
        val started = journal(TimeControl(30, 0)).single()

        assertThat(started.code).isEqualTo("session-started")
        assertThat(started.method).isEqualTo(PatchMethod.PUT)
        assertThat(started.path).isEmpty()
        assertThat(started.value).isEqualTo(
            mapOf(
                "status" to "IN_PROGRESS",
                "meta" to mapOf(
                    "game" to mapOf(
                        "code" to "nothing",
                        "settings" to mapOf(
                            "gameCode" to "nothing",
                            "rounds" to 2,
                            "timeControl" to mapOf("enabled" to true, "baseSeconds" to 30, "incrementSeconds" to 0),
                        ),
                    ),
                    "advancedRules" to null,
                ),
                "state" to mapOf(
                    "players" to listOf(player(ALICE, listOf(PASS)), player(BOB, emptyList())),
                    "timer" to mapOf(
                        "status" to "RUNNING",
                        "deadline" to "2026-10-21T10:00:30Z",
                        "pausedAt" to null,
                        "clocks" to listOf(clock(ALICE, 30_000), clock(BOB, 30_000)),
                    ),
                    "turns" to mapOf("currentPlayerId" to ALICE.toString(), "round" to 1, "rounds" to 2),
                    "result" to null,
                    "moves" to 0,
                ),
            ),
        )
    }

    @Test
    fun `move produces game and core events in order`() {
        val journal = journal(TimeControl(30, 5))

        val events = accepted(fold(journal), ALICE, NOW.plusSeconds(1))

        assertThat(events.map { Triple(it.code, it.method, it.path) }).containsExactly(
            Triple("player-passed", PatchMethod.PATCH, listOf(key("state"))),
            Triple("turn-changed", PatchMethod.PUT, listOf(key("state"), key("turns"))),
            Triple("move-options-changed", PatchMethod.PUT, playerOptions(ALICE)),
            Triple("move-options-changed", PatchMethod.PUT, playerOptions(BOB)),
            Triple("clock-switched", PatchMethod.PUT, listOf(key("state"), key("timer"))),
        )
        assertThat(events.map { it.value }).containsExactly(
            mapOf("moves" to 1),
            mapOf("currentPlayerId" to BOB.toString(), "round" to 1, "rounds" to 2),
            emptyList<Any>(),
            listOf(PASS),
            // Алиса потратила 1 с из 30 и получила добавку 5 с; у Боба полный запас.
            mapOf(
                "status" to "RUNNING",
                "deadline" to "2026-10-21T10:00:31Z",
                "pausedAt" to null,
                "clocks" to listOf(clock(ALICE, 34_000), clock(BOB, 30_000)),
            ),
        )
    }

    @Test
    fun `last move finishes the game, session-finished goes last`() {
        val journal = journal(rounds = 1, players = listOf(ALICE))

        val events = accepted(fold(journal), ALICE, NOW)

        assertThat(events.map { it.code }).containsExactly("player-passed", "turn-changed", "move-options-changed", "session-finished")
        assertThat(events.last().method).isEqualTo(PatchMethod.PATCH)
        assertThat(events.last().path).isEmpty()
        assertThat(events.last().value).isEqualTo(mapOf("status" to "FINISHED"))
        val finished = fold(journal + events).session
        assertThat(finished.status).isEqualTo(SessionStatus.FINISHED)
        assertThat(finished.state.turns).isEqualTo(TurnsState(null, round = 1, rounds = 1))
        assertThat(finished.state.players.single().moveOptions).isEmpty()
        assertThat(finished.state.game).isEqualTo(NothingGameState(moves = 1))
        assertThat(finished.state.result).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("misplacedResults")
    fun `result can be set only once and only together with session-finished`(case: String, events: List<SessionEvent>) {
        val nothing = NothingGameRules()
        @Suppress("UNCHECKED_CAST")
        val setsResult = object : GameRules<NothingGameState, NothingMove, NothingSimpleSettings, NothingAdvancedSettings> by nothing {
            override fun decide(state: SessionState<NothingGameState>, settings: NothingAdvancedSettings, playerId: UUID, move: NothingMove) = Decision.Accepted(events)
        } as GameRules<Any, Any, Any, Any>

        assertThatThrownBy { engine.move(setsResult, fold(journal()), ALICE, NothingMove("PASS"), NOW) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("result-set")
    }

    @Test
    fun `result set together with session-finished is the final result of the game`() {
        val nothing = NothingGameRules()
        @Suppress("UNCHECKED_CAST")
        val drawn = object : GameRules<NothingGameState, NothingMove, NothingSimpleSettings, NothingAdvancedSettings> by nothing {
            override fun decide(state: SessionState<NothingGameState>, settings: NothingAdvancedSettings, playerId: UUID, move: NothingMove) =
                Decision.Accepted(listOf(Result.draw("AGREED"), SessionEvents.finished()))
        } as GameRules<Any, Any, Any, Any>
        val journal = journal()

        val events = (engine.move(drawn, fold(journal), ALICE, NothingMove("PASS"), NOW) as MoveOutcome.Accepted).events

        assertThat(events.map { it.code }).containsExactly("result-set", "move-options-changed", "session-finished")
        assertThat(events.first().path).isEqualTo(listOf(key("state"), key("result")))
        assertThat(events.first().value).isEqualTo(mapOf("outcome" to "DRAW", "winnerIds" to emptyList<Any>(), "reason" to "AGREED"))
        assertThat(engine.mask(rules, events.first(), ViewerKey.Public)).isEqualTo(events.first())
        val session = fold(journal + events).session
        assertThat(session.status).isEqualTo(SessionStatus.FINISHED)
        assertThat(session.state.result).isEqualTo(ResultState(ResultOutcome.DRAW, emptyList(), "AGREED"))
    }

    @Test
    fun `end and cancel stop the timer right before the last event`() {
        val timed = TimeControl(30, 5)
        val finishing = journal(timed, rounds = 1, players = listOf(ALICE))
        val finished = accepted(fold(finishing), ALICE, NOW.plusSeconds(4))
        val cancelled = engine.cancel(fold(journal(timed)), NOW.plusSeconds(10))

        assertThat(finished.takeLast(2).map { it.code }).containsExactly("timer-stopped", "session-finished")
        assertThat(cancelled.map { it.code }).containsExactly("move-options-changed", "timer-stopped", "session-cancelled")
        assertThat(cancelled.first().path).isEqualTo(playerOptions(ALICE))
        assertThat(cancelled.last().value).isEqualTo(mapOf("status" to "CANCELLED"))
        // Остановленные часы хранят итоговые остатки: у ходившего — без добавки.
        assertThat(fold(finishing + finished).session.state.timer)
            .isEqualTo(TimerState(TimerStatus.STOPPED, null, null, listOf(PlayerClock(ALICE, 26_000))))
        assertThat(fold(journal(timed) + cancelled).session.state.timer)
            .isEqualTo(TimerState(TimerStatus.STOPPED, null, null, listOf(PlayerClock(ALICE, 20_000), PlayerClock(BOB, 30_000))))
        assertThat(fold(journal(timed) + cancelled).session.status).isEqualTo(SessionStatus.CANCELLED)
        assertThat(engine.cancel(fold(journal()), NOW).map { it.code }).containsExactly("move-options-changed", "session-cancelled")
    }

    @Test
    fun `rounds advance and the game lasts the configured number of rounds`() {
        val journal = journal(rounds = 2).toMutableList()
        listOf(ALICE, BOB, ALICE).forEach { journal += accepted(fold(journal), it, NOW) }

        val session = fold(journal)
        assertThat(session.session.state.turns).isEqualTo(TurnsState(BOB, round = 2, rounds = 2))
        assertThat(session.session.status).isEqualTo(SessionStatus.IN_PROGRESS)

        journal += accepted(session, BOB, NOW)
        assertThat(fold(journal).session.status).isEqualTo(SessionStatus.FINISHED)
        assertThat(fold(journal).session.state.game).isEqualTo(NothingGameState(moves = 4))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    fun `rules reject moves`(case: String, player: UUID, type: String, code: String) {
        val outcome = engine.move(rules, fold(journal()), player, NothingMove(type), NOW)

        assertThat(outcome).isInstanceOf(MoveOutcome.Rejected::class.java)
        assertThat((outcome as MoveOutcome.Rejected).code).isEqualTo(code)
    }

    @Test
    fun `timeout passes the move of the current player as INFO, the move counter is not changed`() {
        val journal = journal(TimeControl(10, 0))

        val events = engine.timeout(rules, fold(journal), NOW.plusSeconds(10))

        val passed = events.first()
        assertThat(passed.code).isEqualTo("player-passed")
        assertThat(passed.method).isEqualTo(PatchMethod.INFO)
        assertThat(passed.path).isEmpty()
        assertThat(passed.value).isEqualTo(mapOf("playerId" to ALICE.toString(), "timeout" to true))
        assertThat(events.last().code).isEqualTo("clock-switched")
        val after = fold(journal + events).session
        // У Алисы запас кончился, часы перешли к Бобу с его полным запасом.
        assertThat(after.state.timer)
            .isEqualTo(TimerState(TimerStatus.RUNNING, NOW.plusSeconds(20), null, listOf(PlayerClock(ALICE, 0), PlayerClock(BOB, 10_000))))
        assertThat(after.state.turns.currentPlayerId).isEqualTo(BOB)
        assertThat(after.state.game).isEqualTo(NothingGameState(moves = 0))
    }

    @Test
    fun `pause is a timer action, moves are rejected on pause, resume shifts the deadline`() {
        val journal = journal(TimeControl(30, 0)).toMutableList()
        val clocks = listOf(PlayerClock(ALICE, 30_000), PlayerClock(BOB, 30_000))

        val paused = (engine.pause(fold(journal), NOW.plusSeconds(5)) as TimerOutcome.Changed).events
        assertThat(paused.map { Triple(it.code, it.method, it.path) })
            .containsExactly(Triple("game-paused", PatchMethod.PATCH, listOf(key("state"), key("timer"))))
        assertThat(paused.single().value).isEqualTo(mapOf("status" to "PAUSED", "pausedAt" to "2026-10-21T10:00:05Z"))
        journal += paused
        assertThat(fold(journal).session.state.timer).isEqualTo(TimerState(TimerStatus.PAUSED, NOW.plusSeconds(30), NOW.plusSeconds(5), clocks))
        assertThat(engine.pause(fold(journal), NOW.plusSeconds(6))).isEqualTo(TimerOutcome.Unchanged)
        assertThat(engine.move(rules, fold(journal), ALICE, NothingMove("PASS"), NOW)).isEqualTo(MoveOutcome.Paused)
        assertThat(engine.timeout(rules, fold(journal), NOW.plusSeconds(60))).isEmpty()

        val resumed = (engine.resume(fold(journal), NOW.plusSeconds(65)) as TimerOutcome.Changed).events
        assertThat(resumed.single().code).isEqualTo("game-resumed")
        assertThat(resumed.single().method).isEqualTo(PatchMethod.PATCH)
        journal += resumed
        assertThat(fold(journal).session.state.timer).isEqualTo(TimerState(TimerStatus.RUNNING, NOW.plusSeconds(90), null, clocks))
        assertThat(engine.resume(fold(journal), NOW.plusSeconds(66))).isEqualTo(TimerOutcome.Unchanged)
    }

    @Test
    fun `clocks switch only when the turn changes`() {
        val nothing = NothingGameRules()
        @Suppress("UNCHECKED_CAST")
        val keepsTurn = object : GameRules<NothingGameState, NothingMove, NothingSimpleSettings, NothingAdvancedSettings> by nothing {
            override fun decide(state: SessionState<NothingGameState>, settings: NothingAdvancedSettings, playerId: UUID, move: NothingMove) =
                Decision.Accepted(listOf(SessionEvents.info("noted", emptyMap<String, Any>())))
        } as GameRules<Any, Any, Any, Any>
        val journal = journal(TimeControl(30, 5))

        val events = (engine.move(keepsTurn, fold(journal), ALICE, NothingMove("PASS"), NOW.plusSeconds(7)) as MoveOutcome.Accepted).events

        assertThat(events.map { it.code }).containsExactly("noted")
        assertThat(fold(journal + events).session.state.timer).isEqualTo(fold(journal).session.state.timer)
    }

    @Test
    fun `game without a timer cannot be paused`() {
        assertThat(engine.pause(fold(journal()), NOW)).isEqualTo(TimerOutcome.NotPausable)
        assertThat(fold(journal()).session.state.timer).isNull()
    }

    @Test
    fun `move options of other players are masked, events are still delivered`() {
        val journal = journal()
        val events = accepted(fold(journal), ALICE, NOW)

        val forCarol = events.map { engine.mask(rules, it, ViewerKey.Public) }
        val forBob = events.map { engine.mask(rules, it, ViewerKey.Player(BOB)) }

        assertThat(forCarol.map { it.code to it.path }).isEqualTo(events.map { it.code to it.path })
        assertThat(forCarol.filter { it.code == "move-options-changed" }.map { it.value }).containsExactly(emptyList<Any>(), emptyList<Any>())
        assertThat(forBob.filter { it.code == "move-options-changed" }.map { it.value }).containsExactly(emptyList<Any>(), listOf(PASS))
        val started = engine.mask(rules, journal.first(), ViewerKey.Player(BOB))
        assertThat(fold(listOf(started)).session.state.players.map { it.moveOptions }).containsExactly(emptyList(), emptyList())
    }

    /** Снапшот зрителя — свёртка его маскированных событий; она совпадает с маской полной свёртки. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("viewers")
    fun `fold of masked events equals masked full fold`(viewer: ViewerKey) {
        val journal = journal(TimeControl(30, 5)).toMutableList()
        journal += accepted(fold(journal), ALICE, NOW)
        journal += (engine.pause(fold(journal), NOW) as TimerOutcome.Changed).events
        journal += (engine.resume(fold(journal), NOW.plusSeconds(3)) as TimerOutcome.Changed).events
        journal += accepted(fold(journal), BOB, NOW)
        journal += engine.timeout(rules, fold(journal), NOW)

        val ofMasked = SessionEngine.fold(null, journal.map { engine.mask(rules, it, viewer) })
        val full = fold(journal).tree
        @Suppress("UNCHECKED_CAST")
        val state = full.getValue("state") as Map<String, Any?>
        val players = (state.getValue("players") as List<Map<String, Any?>>)
            .map { if (viewer.sees(UUID.fromString(it["id"] as String))) it else it + ("moveOptions" to emptyList<Any>()) }
        val maskedFull = full + ("state" to state + ("players" to players))

        assertThat(ofMasked).isEqualTo(maskedFull)
    }

    private fun journal(
        timeControl: TimeControl? = null,
        rounds: Int = 2,
        players: List<UUID> = listOf(ALICE, BOB),
    ): List<SessionEvent> {
        val clock = TimeControlSettings(timeControl != null, timeControl?.baseSeconds ?: 30, timeControl?.incrementSeconds ?: 5)
        return engine.start(rules, players, seed = 1, NothingAdvancedSettings(rounds, clock), NOW)
    }

    private fun fold(events: List<SessionEvent>): SessionDocument = engine.read(rules, SessionEngine.fold(null, events)!!)

    private fun accepted(document: SessionDocument, player: UUID, now: Instant): List<SessionEvent> =
        (engine.move(rules, document, player, NothingMove("PASS"), now) as MoveOutcome.Accepted).events

    // В JSON-дереве число — Int, как его читает кодек.
    private fun clock(player: UUID, remainingMs: Int) = mapOf("playerId" to player.toString(), "remainingMs" to remainingMs)

    companion object {
        private val ALICE = UUID.fromString("01990000-0000-7000-8000-00000000000a")
        private val BOB = UUID.fromString("01990000-0000-7000-8000-00000000000b")
        private val NOW = Instant.parse("2026-10-21T10:00:00Z")
        private val PASS = mapOf("type" to "PASS", "data" to null)

        private fun key(name: String) = PathSegment.Key(name)

        private fun playerOptions(id: UUID) = listOf(key("state"), key("players"), PathSegment.Id(id.toString()), key("moveOptions"))

        private fun player(id: UUID, options: List<Any>) = mapOf("id" to id.toString(), "moveOptions" to options)

        @JvmStatic
        fun viewers(): Stream<ViewerKey> = Stream.of(ViewerKey.Full, ViewerKey.Public, ViewerKey.Player(ALICE), ViewerKey.Player(BOB))

        @JvmStatic
        fun misplacedResults(): Stream<Arguments> = Stream.of(
            arguments("result without session-finished", listOf(Result.win(ALICE, "X"))),
            arguments("result twice", listOf(Result.win(ALICE, "X"), Result.draw("Y"), SessionEvents.finished())),
        )

        @JvmStatic
        fun rejections(): Stream<Arguments> = Stream.of(
            arguments("not your turn", BOB, "PASS", "NOT_YOUR_TURN"),
            arguments("unknown move", ALICE, "JUMP", "UNKNOWN_MOVE"),
        )
    }
}
