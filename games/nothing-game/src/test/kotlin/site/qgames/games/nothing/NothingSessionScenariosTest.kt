package site.qgames.games.nothing

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
import site.qgames.games.api.core.PlayerState
import site.qgames.games.api.session.MoveOutcome
import site.qgames.games.api.session.Session
import site.qgames.games.api.session.SessionEngine
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionEvents
import site.qgames.games.api.session.SessionStatus
import site.qgames.games.api.session.TimerOutcome
import site.qgames.games.api.session.ViewerKey
import site.qgames.games.api.timer.TimerState
import site.qgames.games.api.timer.TimerStatus
import site.qgames.games.api.turns.TurnsState
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

/**
 * Свёртка и маскировка партии «игры в ничто» на платформе (модули core, turns, timer) — чистые функции, без сервиса.
 */
class NothingSessionScenariosTest {

    @Suppress("UNCHECKED_CAST")
    private val rules = NothingGameRules() as GameRules<Any, Any, Any>

    @Test
    fun `move produces game and module events in order`() {
        val journal = journal(TimeControl(TimeControlMode.PER_MOVE, 30))

        val events = accepted(fold(journal), ALICE, NOW.plusSeconds(1))

        assertThat(events.map { it.type }).containsExactly(
            "moved", "turns-changed", "player-changed", "player-changed", "timer-changed",
        )
        assertThat(events[0].data).isEqualTo(NothingMoved(ALICE, "PASS", timeout = false))
        assertThat(events[1].data).isEqualTo(TurnsState(BOB, round = 1, rounds = 2))
        assertThat(events[2].data).isEqualTo(PlayerState(ALICE, emptyList()))
        assertThat(events[3].data).isEqualTo(PlayerState(BOB, listOf(MoveOption("PASS"))))
        assertThat(events[4].data).isEqualTo(TimerState(TimerStatus.RUNNING, NOW.plusSeconds(31), null))
    }

    @Test
    fun `last move finishes the game, session-finished goes last`() {
        val journal = journal(rounds = 1, players = listOf(ALICE)).toMutableList()

        val events = accepted(fold(journal), ALICE, NOW)

        assertThat(events.map { it.type }).containsExactly("moved", "turns-changed", "player-changed", "session-finished")
        val finished = fold(journal + events)
        assertThat(finished.status).isEqualTo(SessionStatus.FINISHED)
        assertThat(finished.state.turns).isEqualTo(TurnsState(null, round = 1, rounds = 1))
        assertThat(finished.state.players.single().moveOptions).isEmpty()
        assertThat(finished.state.game).isEqualTo(NothingGameState(moves = 1))
    }

    @Test
    fun `end and cancel stop the timer right before the last event`() {
        val timed = TimeControl(TimeControlMode.PER_MOVE, 30)
        val finishing = journal(timed, rounds = 1, players = listOf(ALICE))
        val finished = accepted(fold(finishing), ALICE, NOW)
        val cancelled = SessionEngine.cancel(fold(journal(timed)))

        assertThat(finished.takeLast(2).map { it.type }).containsExactly("timer-changed", "session-finished")
        assertThat(cancelled.map { it.type }).containsExactly("player-changed", "timer-changed", "session-cancelled")
        assertThat(cancelled.first().data).isEqualTo(PlayerState(ALICE, emptyList()))
        val stopped = TimerState(TimerStatus.STOPPED, null, null)
        assertThat(fold(finishing + finished).state.timer).isEqualTo(stopped)
        assertThat(fold(journal(timed) + cancelled).state.timer).isEqualTo(stopped)
        assertThat(SessionEngine.cancel(fold(journal())).map { it.type }).containsExactly("player-changed", "session-cancelled")
    }

    @Test
    fun `rounds advance and the game lasts the configured number of rounds`() {
        val journal = journal(rounds = 2).toMutableList()
        listOf(ALICE, BOB, ALICE).forEach { journal += accepted(fold(journal), it, NOW) }

        val session = fold(journal)
        assertThat(session.state.turns).isEqualTo(TurnsState(BOB, round = 2, rounds = 2))
        assertThat(session.status).isEqualTo(SessionStatus.IN_PROGRESS)

        journal += accepted(session, BOB, NOW)
        assertThat(fold(journal).status).isEqualTo(SessionStatus.FINISHED)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    fun `rules reject moves`(case: String, player: UUID, type: String, code: String) {
        val outcome = SessionEngine.move(rules, fold(journal()), player, NothingMove(type), NOW)

        assertThat(outcome).isInstanceOf(MoveOutcome.Rejected::class.java)
        assertThat((outcome as MoveOutcome.Rejected).code).isEqualTo(code)
    }

    @Test
    fun `timeout passes the move of the current player`() {
        val session = fold(journal(TimeControl(TimeControlMode.PER_MOVE, 10)))

        val events = SessionEngine.timeout(rules, session, NOW.plusSeconds(10))

        assertThat(events.first().data).isEqualTo(NothingMoved(ALICE, "PASS", timeout = true))
        assertThat(events.last().data).isEqualTo(TimerState(TimerStatus.RUNNING, NOW.plusSeconds(20), null))
    }

    @Test
    fun `pause is a timer action, moves are rejected on pause, resume shifts the deadline`() {
        val journal = journal(TimeControl(TimeControlMode.PER_MOVE, 30)).toMutableList()

        journal += (SessionEngine.pause(fold(journal), NOW.plusSeconds(5)) as TimerOutcome.Changed).events
        assertThat(fold(journal).state.timer).isEqualTo(TimerState(TimerStatus.PAUSED, NOW.plusSeconds(30), NOW.plusSeconds(5)))
        assertThat(SessionEngine.pause(fold(journal), NOW.plusSeconds(6))).isEqualTo(TimerOutcome.Unchanged)
        assertThat(SessionEngine.move(rules, fold(journal), ALICE, NothingMove("PASS"), NOW)).isEqualTo(MoveOutcome.Paused)
        assertThat(SessionEngine.timeout(rules, fold(journal), NOW.plusSeconds(60))).isEmpty()

        journal += (SessionEngine.resume(fold(journal), NOW.plusSeconds(65)) as TimerOutcome.Changed).events
        assertThat(fold(journal).state.timer).isEqualTo(TimerState(TimerStatus.RUNNING, NOW.plusSeconds(90), null))
        assertThat(SessionEngine.resume(fold(journal), NOW.plusSeconds(66))).isEqualTo(TimerOutcome.Unchanged)
    }

    @Test
    fun `game without a timer cannot be paused`() {
        assertThat(SessionEngine.pause(fold(journal()), NOW)).isEqualTo(TimerOutcome.NotPausable)
        assertThat(fold(journal()).state.timer).isNull()
    }

    @Test
    fun `move options of other players are masked, events are still delivered`() {
        val journal = journal()
        val events = accepted(fold(journal), ALICE, NOW)

        val forCarol = events.map { SessionEngine.mask(rules, it, ViewerKey.Public) }
        val forBob = events.map { SessionEngine.mask(rules, it, ViewerKey.Player(BOB)) }

        assertThat(forCarol.map { it.type }).isEqualTo(events.map { it.type })
        assertThat(forCarol.filter { it.type == SessionEvents.PLAYER_CHANGED }.map { it.data })
            .containsExactly(PlayerState(ALICE, emptyList()), PlayerState(BOB, emptyList()))
        assertThat(forBob.filter { it.type == SessionEvents.PLAYER_CHANGED }.map { it.data })
            .containsExactly(PlayerState(ALICE, emptyList()), PlayerState(BOB, listOf(MoveOption("PASS"))))
        val started = SessionEngine.mask(rules, journal.first(), ViewerKey.Player(BOB))
        assertThat(SessionEngine.fold(rules, null, listOf(started))!!.state.players.map { it.moveOptions })
            .containsExactly(emptyList(), emptyList())
    }

    /** Снапшот зрителя — свёртка его маскированных событий; она совпадает с маской полной свёртки. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("viewers")
    fun `fold of masked events equals masked full fold`(viewer: ViewerKey) {
        val journal = journal(TimeControl(TimeControlMode.PER_MOVE, 30)).toMutableList()
        journal += accepted(fold(journal), ALICE, NOW)
        journal += (SessionEngine.pause(fold(journal), NOW) as TimerOutcome.Changed).events
        journal += (SessionEngine.resume(fold(journal), NOW.plusSeconds(3)) as TimerOutcome.Changed).events
        journal += accepted(fold(journal), BOB, NOW)
        journal += SessionEngine.timeout(rules, fold(journal), NOW)

        val ofMasked = SessionEngine.fold(rules, null, journal.map { SessionEngine.mask(rules, it, viewer) })!!
        val full = fold(journal)
        val maskedFull = full.copy(
            state = full.state.copy(players = full.state.players.map { if (viewer.sees(it.id)) it else it.copy(moveOptions = emptyList()) }),
        )

        assertThat(ofMasked).isEqualTo(maskedFull)
    }

    private fun journal(
        timeControl: TimeControl = TimeControl(TimeControlMode.NONE, null),
        rounds: Int = 2,
        players: List<UUID> = listOf(ALICE, BOB),
    ): List<SessionEvent> = SessionEngine.start(rules, players, seed = 1, NothingSettings(rounds), timeControl, NOW)

    private fun fold(events: List<SessionEvent>): Session = SessionEngine.fold(rules, null, events)!!

    private fun accepted(session: Session, player: UUID, now: Instant): List<SessionEvent> =
        (SessionEngine.move(rules, session, player, NothingMove("PASS"), now) as MoveOutcome.Accepted).events

    companion object {
        private val ALICE = UUID.fromString("01990000-0000-7000-8000-00000000000a")
        private val BOB = UUID.fromString("01990000-0000-7000-8000-00000000000b")
        private val NOW = Instant.parse("2026-10-21T10:00:00Z")

        @JvmStatic
        fun viewers(): Stream<ViewerKey> = Stream.of(ViewerKey.Full, ViewerKey.Public, ViewerKey.Player(ALICE), ViewerKey.Player(BOB))

        @JvmStatic
        fun rejections(): Stream<Arguments> = Stream.of(
            arguments("not your turn", BOB, "PASS", "NOT_YOUR_TURN"),
            arguments("unknown move", ALICE, "JUMP", "UNKNOWN_MOVE"),
        )
    }
}
