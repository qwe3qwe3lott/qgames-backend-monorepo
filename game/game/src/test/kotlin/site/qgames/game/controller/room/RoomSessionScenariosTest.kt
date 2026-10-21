package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.gatewayapi.dto.room.RoomStatus
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.support.TestData.DAVE
import site.qgames.games.api.core.MoveOption
import site.qgames.games.api.timer.TimerState
import site.qgames.games.api.timer.TimerStatus
import site.qgames.games.api.turns.TurnsState
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.stream.Stream

/**
 * Партии «игры в ничто»: игроки по очереди делают PASS, после заданного в настройках числа кругов (по умолчанию 3)
 * партия заканчивается. Партия — журнал событий (room_session_event), состояние — его свёртка. При таймере на ход
 * проекция дедлайна хранится в room_session.move_deadline.
 */
class RoomSessionScenariosTest : BaseIntegrationTest() {

    /** Готовит комнату для сценария и возвращает её id. */
    fun interface RoomSetup {
        fun RoomSessionScenariosTest.prepare(): UUID
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedStarts")
    fun `start is rejected`(case: String, setup: RoomSetup, user: UUID, expectedStatus: HttpStatus, expectedCode: String) {
        val roomId = with(setup) { prepare() }
        val sessionsBefore = countRows("room_session")

        call(HttpMethod.POST, "/api/rooms/$roomId/session", user).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            jsonPath("$.code") { value(expectedCode) }
        }
        assertThat(countRows("room_session")).isEqualTo(sessionsBefore)
    }

    @Test
    fun `owner starts the game with current players in join order`() {
        val roomId = roomWith(ALICE, BOB, CAROL)
        changeRole(CAROL, roomId, RoomRole.SPECTATOR)

        val room = start(ALICE, roomId)

        assertThat(room.status).isEqualTo(RoomStatus.IN_GAME)
        val session = room.session!!
        assertThat(session.status).isEqualTo(SessionStatus.IN_PROGRESS)
        assertThat(journal(roomId)).containsExactly(1L to "started")
        val state = fullState(roomId).state
        assertThat(state.players.map { it.id }).containsExactly(ALICE, BOB)
        assertThat(state.players.map { it.moveOptions }).containsExactly(listOf(MoveOption(PASS)), emptyList())
        assertThat(state.turns).isEqualTo(TurnsState(ALICE, round = 1, rounds = 3))
        assertThat(state.timer).isNull()
        assertThat(countRows("room_session WHERE last_seq = 1")).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0} player(s)")
    @ValueSource(ints = [1, 2, 4])
    fun `game ends after three rounds and the room is ready for the next one`(playerCount: Int) {
        val players = listOf(ALICE, BOB, CAROL, DAVE).take(playerCount)
        val roomId = roomWith(players.first(), *players.drop(1).toTypedArray(), started = true)
        val first = sessionId(roomId)

        repeat(3) { players.forEach { pass(it, roomId) } }

        val room = room(roomId, ALICE)
        assertThat(room.status).isEqualTo(RoomStatus.WAITING)
        assertThat(room.session!!.status).isEqualTo(SessionStatus.FINISHED)
        val journal = journal(roomId)
        assertThat(journal.map { it.first }).containsExactlyElementsOf((1L..journal.size).toList())
        assertThat(journal.count { it.second == "moved" }).isEqualTo(3 * playerCount)
        assertThat(journal.last().second).isEqualTo("session-finished")
        assertThat(countRows("room_session WHERE status = 'FINISHED' AND finished_at IS NOT NULL AND last_seq = :n", "n" to journal.size.toLong()))
            .isEqualTo(1)

        val next = start(players.first(), roomId)
        assertThat(next.session!!.id).isNotEqualTo(first)
        assertThat(next.session!!.status).isEqualTo(SessionStatus.IN_PROGRESS)
    }

    @ParameterizedTest(name = "{0} round(s), {1} player(s)")
    @CsvSource("1, 1", "1, 3", "2, 2", "5, 2")
    fun `game lasts the number of rounds from the room settings`(rounds: Int, playerCount: Int) {
        val players = listOf(ALICE, BOB, CAROL, DAVE).take(playerCount)
        val roomId = roomWith(players.first(), *players.drop(1).toTypedArray(), started = true, settings = nothingSettings(rounds = rounds))

        repeat(rounds) { round ->
            assertThat(fullState(roomId).state.turns).isEqualTo(TurnsState(players.first(), round + 1, rounds))
            players.forEach { player ->
                assertThat(room(roomId, ALICE).status).isEqualTo(RoomStatus.IN_GAME)
                pass(player, roomId)
            }
        }

        assertThat(room(roomId, ALICE).status).isEqualTo(RoomStatus.WAITING)
        assertThat(fullState(roomId).state.turns).isEqualTo(TurnsState(null, rounds, rounds))
    }

    @Test
    fun `move timer starts with the game and every move resets it`() {
        val roomId = roomWith(ALICE, BOB, settings = nothingSettings(mode = TimeControlMode.PER_MOVE, moveSeconds = 30))

        val beforeStart = Instant.now()
        start(ALICE, roomId)
        val afterStart = Instant.now()
        val firstDeadline = fullState(roomId).state.timer!!.deadline!!
        assertThat(firstDeadline).isBetween(beforeStart.plusSeconds(30).minusMillis(1), afterStart.plusSeconds(30))
        assertThat(storedDeadline(roomId)).isCloseTo(firstDeadline, within(1, ChronoUnit.MILLIS))

        val beforeMove = Instant.now()
        pass(ALICE, roomId)
        val afterMove = Instant.now()
        val secondDeadline = fullState(roomId).state.timer!!.deadline!!
        assertThat(secondDeadline).isBetween(beforeMove.plusSeconds(30).minusMillis(1), afterMove.plusSeconds(30))
        assertThat(storedDeadline(roomId)).isCloseTo(secondDeadline, within(1, ChronoUnit.MILLIS))
        assertThat(journal(roomId).last().second).isEqualTo("timer-changed")

        cancel(ALICE, roomId)
        assertThat(storedDeadline(roomId)).isNull()
        assertThat(journal(roomId).takeLast(3).map { it.second }).containsExactly("player-changed", "timer-changed", "session-cancelled")
        assertThat(fullState(roomId).state.timer).isEqualTo(TimerState(TimerStatus.STOPPED, null, null))
    }

    @Test
    fun `game without a timer has no deadline`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        pass(ALICE, roomId)

        assertThat(fullState(roomId).state.timer).isNull()
        assertThat(journal(roomId).map { it.second }).doesNotContain("timer-changed")
        assertThat(storedDeadline(roomId)).isNull()
    }

    @Test
    fun `finished game has no deadline`() {
        val roomId = roomWith(ALICE, started = true, settings = nothingSettings(rounds = 1, mode = TimeControlMode.PER_MOVE, moveSeconds = 5))

        pass(ALICE, roomId)

        assertThat(room(roomId, ALICE).session!!.status).isEqualTo(SessionStatus.FINISHED)
        assertThat(storedDeadline(roomId)).isNull()
        assertThat(journal(roomId).takeLast(2).map { it.second }).containsExactly("timer-changed", "session-finished")
        assertThat(fullState(roomId).state.timer).isEqualTo(TimerState(TimerStatus.STOPPED, null, null))
    }

    @Test
    fun `accepted move answers 204 without a body and is written to the journal`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        val response = moveRequest(ALICE, roomId).andExpect { status { isNoContent() } }.andReturn().response

        assertThat(response.contentAsString).isEmpty()
        assertThat(journal(roomId).map { it.second })
            .containsExactly("started", "moved", "turns-changed", "player-changed", "player-changed")
        val moved = jdbcClient.sql("SELECT data::text FROM room_session_event WHERE type = 'moved'").query(String::class.java).single()
        assertThat(jsonMapper.readTree(moved)).isEqualTo(
            jsonMapper.readTree("""{"playerId": "$ALICE", "type": "PASS", "timeout": false}"""),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedMoves")
    fun `move is rejected without changing the game`(
        case: String,
        setup: RoomSetup,
        user: UUID,
        moveType: String,
        expectedStatus: HttpStatus,
        expectedCode: String,
        expectedReason: String?,
    ) {
        val roomId = with(setup) { prepare() }
        val eventsBefore = countRows("room_session_event")

        moveRequest(user, roomId, moveType).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            jsonPath("$.code") { value(expectedCode) }
            if (expectedReason != null) jsonPath("$.reason") { value(expectedReason) }
        }
        assertThat(countRows("room_session_event")).isEqualTo(eventsBefore)
    }

    @Test
    fun `move with an unknown game code is rejected`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        moveRequest(ALICE, roomId, gameCode = "chess").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
        assertThat(journal(roomId)).hasSize(1)
    }

    @Test
    fun `player who left keeps the seat and continues after returning`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        leaveCompletely(BOB, roomId)

        pass(ALICE, roomId)
        assertThat(room(roomId, ALICE).status).isEqualTo(RoomStatus.IN_GAME)
        assertThat(fullState(roomId).state.players.map { it.id }).containsExactly(ALICE, BOB)

        assertThat(join(BOB, roomId).members.single { it.userId == BOB }.role).isEqualTo(RoomRole.PLAYER)
        pass(BOB, roomId)
        assertThat(fullState(roomId).state.turns.round).isEqualTo(2)
    }

    @Test
    fun `owner cancels the game and the room is ready again`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        pass(ALICE, roomId)

        call(HttpMethod.DELETE, "/api/rooms/$roomId/session", ALICE).andExpect { status { isNoContent() } }

        val room = room(roomId, ALICE)
        assertThat(room.status).isEqualTo(RoomStatus.WAITING)
        assertThat(room.session!!.status).isEqualTo(SessionStatus.CANCELLED)
        // Отмена, как и конец партии, обнуляет варианты ходов: у Боба был PASS.
        assertThat(journal(roomId).takeLast(2).map { it.second }).containsExactly("player-changed", "session-cancelled")
        assertThat(fullState(roomId).state.players.map { it.moveOptions }).containsOnly(emptyList())
        assertThat(start(ALICE, roomId).session!!.status).isEqualTo(SessionStatus.IN_PROGRESS)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedCancels")
    fun `cancel is rejected`(case: String, setup: RoomSetup, user: UUID, expectedStatus: HttpStatus, expectedCode: String) {
        val roomId = with(setup) { prepare() }

        call(HttpMethod.DELETE, "/api/rooms/$roomId/session", user).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            jsonPath("$.code") { value(expectedCode) }
        }
    }

    @Test
    fun `room response has the session briefly`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        call(HttpMethod.GET, "/api/rooms/$roomId", CAROL).andExpect {
            status { isOk() }
            jsonPath("$.session.id") { value(sessionId(roomId).toString()) }
            jsonPath("$.session.status") { value("IN_PROGRESS") }
            jsonPath("$.session.state") { doesNotExist() }
            jsonPath("$.session.version") { doesNotExist() }
        }
    }

    private fun storedDeadline(roomId: UUID): Instant? =
        jdbcClient.sql("SELECT move_deadline FROM room_session WHERE room_id = :id ORDER BY created_at DESC LIMIT 1")
            .param("id", roomId)
            .query { rs, _ -> rs.getTimestamp("move_deadline")?.toInstant() }
            .list()
            .single()

    companion object {
        private fun started(): RoomSetup = RoomSetup { roomWith(ALICE, BOB, started = true) }

        @JvmStatic
        fun rejectedStarts(): Stream<Arguments> = Stream.of(
            arguments("not the owner", RoomSetup { roomWith(ALICE, BOB) }, BOB, HttpStatus.FORBIDDEN, "NOT_ROOM_OWNER"),
            arguments(
                "no players: owner became a spectator",
                RoomSetup { roomWith(ALICE).also { changeRole(ALICE, it, RoomRole.SPECTATOR) } },
                ALICE, HttpStatus.CONFLICT, "NOT_ENOUGH_PLAYERS",
            ),
            arguments("game already running", started(), ALICE, HttpStatus.CONFLICT, "ROOM_NOT_WAITING"),
        )

        @JvmStatic
        fun rejectedMoves(): Stream<Arguments> = Stream.of(
            arguments("not your turn", started(), BOB, PASS, HttpStatus.UNPROCESSABLE_CONTENT, "MOVE_REJECTED", "NOT_YOUR_TURN"),
            arguments("unknown move", started(), ALICE, "JUMP", HttpStatus.UNPROCESSABLE_CONTENT, "MOVE_REJECTED", "UNKNOWN_MOVE"),
            arguments("move without a type", started(), ALICE, "", HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", null),
            arguments(
                "spectator",
                RoomSetup { roomWith(ALICE, BOB, started = true).also { join(CAROL, it) } },
                CAROL, PASS, HttpStatus.FORBIDDEN, "NOT_SESSION_PLAYER", null,
            ),
            arguments(
                "player who left the room",
                RoomSetup { roomWith(ALICE, BOB, started = true).also { leaveCompletely(ALICE, it) } },
                ALICE, PASS, HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER", null,
            ),
            arguments("no game", RoomSetup { roomWith(ALICE, BOB) }, ALICE, PASS, HttpStatus.CONFLICT, "ROOM_NOT_IN_GAME", null),
        )

        @JvmStatic
        fun rejectedCancels(): Stream<Arguments> = Stream.of(
            arguments("not the owner", started(), BOB, HttpStatus.FORBIDDEN, "NOT_ROOM_OWNER"),
            arguments("no game", RoomSetup { roomWith(ALICE) }, ALICE, HttpStatus.CONFLICT, "ROOM_NOT_IN_GAME"),
        )
    }
}
