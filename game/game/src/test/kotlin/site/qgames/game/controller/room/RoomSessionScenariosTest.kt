package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.gatewayapi.dto.room.RoomStatus
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.support.TestData.DAVE
import java.util.UUID
import java.util.stream.Stream

/**
 * Партии «игры в ничто»: игроки по очереди делают PASS, после 3 кругов партия заканчивается.
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
        assertThat(session.players).containsExactly(ALICE, BOB)
        assertThat(session.version).isZero()
        assertThat(session.state).containsEntry("currentPlayer", ALICE.toString()).containsEntry("round", 1)
    }

    @ParameterizedTest(name = "{0} player(s)")
    @ValueSource(ints = [1, 2, 4])
    fun `game ends after three rounds and the room is ready for the next one`(playerCount: Int) {
        val players = listOf(ALICE, BOB, CAROL, DAVE).take(playerCount)
        val roomId = roomWith(players.first(), *players.drop(1).toTypedArray(), started = true)

        var last: RoomResponse? = null
        repeat(3) { players.forEach { last = pass(it, roomId) } }

        val finished = last!!
        assertThat(finished.status).isEqualTo(RoomStatus.WAITING)
        assertThat(finished.session!!.status).isEqualTo(SessionStatus.FINISHED)
        assertThat(finished.session!!.state).containsEntry("finished", true).containsEntry("currentPlayer", null)
        val seqs = jdbcClient.sql("SELECT seq FROM room_session_move ORDER BY seq").query(Long::class.java).list()
        assertThat(seqs).containsExactlyElementsOf((1L..3L * playerCount).toList())

        val next = start(players.first(), roomId)
        assertThat(next.session!!.id).isNotEqualTo(finished.session!!.id)
        assertThat(next.session!!.status).isEqualTo(SessionStatus.IN_PROGRESS)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedMoves")
    fun `move is rejected without changing the game`(
        case: String,
        setup: RoomSetup,
        user: UUID,
        moveType: String,
        expectedVersion: Long?,
        expectedStatus: HttpStatus,
        expectedCode: String,
        expectedReason: String?,
    ) {
        val roomId = with(setup) { prepare() }
        val movesBefore = countRows("room_session_move")

        moveRequest(user, roomId, moveType, expectedVersion).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            jsonPath("$.code") { value(expectedCode) }
            if (expectedReason != null) jsonPath("$.reason") { value(expectedReason) }
        }
        assertThat(countRows("room_session_move")).isEqualTo(movesBefore)
    }

    @Test
    fun `move with an unknown game code is rejected`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        moveRequest(ALICE, roomId, gameCode = "chess").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
        assertThat(countRows("room_session_move")).isZero()
    }

    @Test
    fun `move envelope is stored in the move log`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        pass(ALICE, roomId)

        val stored = jdbcClient.sql("SELECT move::text FROM room_session_move").query(String::class.java).single()
        assertThat(stored).isEqualTo("""{"type": "PASS"}""")
    }

    @Test
    fun `player who left keeps the seat and continues after returning`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        leave(BOB, roomId)

        val afterAlice = pass(ALICE, roomId)
        assertThat(afterAlice.status).isEqualTo(RoomStatus.IN_GAME)
        assertThat(afterAlice.session!!.players).containsExactly(ALICE, BOB)

        assertThat(join(BOB, roomId).members.single { it.userId == BOB }.role).isEqualTo(RoomRole.PLAYER)
        val afterBob = pass(BOB, roomId)
        assertThat(afterBob.session!!.version).isEqualTo(2)
    }

    @Test
    fun `expected version from the client is honoured`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        val afterAlice = moveRequest(ALICE, roomId, expectedVersion = 0).andExpect { status { isOk() } }.body<RoomResponse>()

        assertThat(afterAlice.session!!.version).isEqualTo(1)
        moveRequest(BOB, roomId, expectedVersion = afterAlice.session!!.version).andExpect { status { isOk() } }
    }

    @Test
    fun `owner cancels the game and the room is ready again`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        pass(ALICE, roomId)

        val cancelled = call(HttpMethod.DELETE, "/api/rooms/$roomId/session", ALICE)
            .andExpect { status { isOk() } }
            .body<RoomResponse>()

        assertThat(cancelled.status).isEqualTo(RoomStatus.WAITING)
        assertThat(cancelled.session!!.status).isEqualTo(SessionStatus.CANCELLED)
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
    fun `spectator sees the game state`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        join(CAROL, roomId)

        val seenByCarol = room(roomId, CAROL)

        assertThat(seenByCarol.session!!.state).containsEntry("currentPlayer", ALICE.toString())
    }

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
            arguments("not your turn", started(), BOB, PASS, null, HttpStatus.UNPROCESSABLE_CONTENT, "MOVE_REJECTED", "NOT_YOUR_TURN"),
            arguments(
                "unknown move", started(), ALICE, "JUMP", null,
                HttpStatus.UNPROCESSABLE_CONTENT, "MOVE_REJECTED", "UNKNOWN_MOVE",
            ),
            arguments(
                "move without a type", started(), ALICE, "", null,
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", null,
            ),
            arguments(
                "spectator",
                RoomSetup { roomWith(ALICE, BOB, started = true).also { join(CAROL, it) } },
                CAROL, PASS, null, HttpStatus.FORBIDDEN, "NOT_SESSION_PLAYER", null,
            ),
            arguments(
                "player who left the room",
                RoomSetup { roomWith(ALICE, BOB, started = true).also { leave(ALICE, it) } },
                ALICE, PASS, null, HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER", null,
            ),
            arguments("stale version", started(), ALICE, PASS, 5L, HttpStatus.CONFLICT, "SESSION_CONFLICT", null),
            arguments("no game", RoomSetup { roomWith(ALICE, BOB) }, ALICE, PASS, null, HttpStatus.CONFLICT, "ROOM_NOT_IN_GAME", null),
        )

        @JvmStatic
        fun rejectedCancels(): Stream<Arguments> = Stream.of(
            arguments("not the owner", started(), BOB, HttpStatus.FORBIDDEN, "NOT_ROOM_OWNER"),
            arguments("no game", RoomSetup { roomWith(ALICE) }, ALICE, HttpStatus.CONFLICT, "ROOM_NOT_IN_GAME"),
        )
    }
}
