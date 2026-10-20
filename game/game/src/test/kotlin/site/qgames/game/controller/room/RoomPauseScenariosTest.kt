package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.room.RoomStatus
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.job.session.MoveTimeoutJob
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.support.TestData.EVE
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

/**
 * Пауза партии: ставит и снимает владелец (POST …/session/pause и …/resume). На паузе ходы запрещены, таймер хода и
 * сроки возвращения игроков партии заморожены, отмена партии работает.
 */
class RoomPauseScenariosTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var moveTimeoutJob: MoveTimeoutJob

    /** Готовит комнату для сценария и возвращает её id. */
    fun interface RoomSetup {
        fun RoomPauseScenariosTest.prepare(): UUID
    }

    @Test
    fun `owner pauses the game, moves are rejected and the deadline is kept`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove())
        val deadline = room(roomId, ALICE).session!!.moveDeadline

        val paused = pause(ALICE, roomId)

        val session = paused.session!!
        assertThat(paused.status).isEqualTo(RoomStatus.IN_GAME)
        assertThat(session.pausedAt).isNotNull()
        assertThat(session.moveDeadline).isEqualTo(deadline)
        moveRequest(ALICE, roomId).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SESSION_PAUSED") }
        }
        assertThat(countRows("room_session_move")).isZero()
        assertThat(room(roomId, BOB).session!!.version).isZero()
    }

    @Test
    fun `repeated pause changes nothing`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        val first = pause(ALICE, roomId).session!!.pausedAt

        val second = pause(ALICE, roomId).session!!.pausedAt

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `resume without a pause changes nothing`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove())
        val before = room(roomId, ALICE).session!!

        val resumed = resume(ALICE, roomId).session!!

        assertThat(resumed.pausedAt).isNull()
        assertThat(resumed.moveDeadline).isEqualTo(before.moveDeadline)
    }

    @Test
    fun `move timeout does not fire on a paused game`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove())
        pause(ALICE, roomId)
        jdbcClient.sql("UPDATE room_session SET move_deadline = now() - interval '5 seconds' WHERE room_id = :id")
            .param("id", roomId)
            .update()

        assertThat(moveTimeoutJob.run()).isZero()

        assertThat(countRows("room_session_move")).isZero()
    }

    @Test
    fun `players of a paused game are not removed, other leavers are`() {
        val roomId = roomWith(ALICE, BOB, started = true).also { join(CAROL, it) }
        leave(BOB, roomId)
        leave(CAROL, roomId)
        pause(ALICE, roomId)
        expireLeave(roomId, BOB)
        expireLeave(roomId, CAROL)

        assertThat(roomLeaveJob.run()).isEqualTo(1)

        val room = room(roomId, ALICE)
        assertThat(room.members.map { it.userId to it.leaving }).containsExactly(ALICE to false, BOB to true)
    }

    @Test
    fun `resume shifts the move deadline and the leave deadlines of the game players by the pause length`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove()).also { join(CAROL, it) }
        leave(BOB, roomId)
        leave(CAROL, roomId)
        pause(ALICE, roomId)
        // Пауза длится минуту.
        jdbcClient.sql("UPDATE room_session SET paused_at = paused_at - interval '60 seconds' WHERE room_id = :id")
            .param("id", roomId)
            .update()
        val pausedAt = room(roomId, ALICE).session!!.pausedAt!!
        val moveDeadline = room(roomId, ALICE).session!!.moveDeadline!!
        val bobDeadline = leaveDeadline(roomId, BOB)!!
        val carolDeadline = leaveDeadline(roomId, CAROL)

        val before = Instant.now()
        val resumed = resume(ALICE, roomId)
        val after = Instant.now()

        val minShift = Duration.between(pausedAt, before).minusMillis(1)
        val maxShift = Duration.between(pausedAt, after)
        val session = resumed.session!!
        assertThat(session.pausedAt).isNull()
        assertThat(session.moveDeadline).isBetween(moveDeadline.plus(minShift), moveDeadline.plus(maxShift))
        assertThat(leaveDeadline(roomId, BOB)).isBetween(bobDeadline.plus(minShift), bobDeadline.plus(maxShift))
        assertThat(leaveDeadline(roomId, CAROL)).isEqualTo(carolDeadline)
        // Ходы снова разрешены.
        assertThat(pass(ALICE, roomId).session!!.version).isEqualTo(1)
    }

    @Test
    fun `owner cancels a paused game`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove())
        pause(ALICE, roomId)

        val cancelled = cancel(ALICE, roomId)

        assertThat(cancelled.status).isEqualTo(RoomStatus.WAITING)
        assertThat(cancelled.session!!.status).isEqualTo(SessionStatus.CANCELLED)
        assertThat(cancelled.session!!.pausedAt).isNull()
        assertThat(countRows("room_session WHERE paused_at IS NULL AND move_deadline IS NULL AND status = 'CANCELLED'")).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("rejections")
    fun `pause and resume are rejected`(
        action: String,
        case: String,
        setup: RoomSetup,
        user: UUID,
        expectedStatus: HttpStatus,
        expectedCode: String,
    ) {
        val roomId = with(setup) { prepare() }

        call(HttpMethod.POST, "/api/rooms/$roomId/session/$action", user).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            jsonPath("$.code") { value(expectedCode) }
        }
        assertThat(countRows("room_session WHERE paused_at IS NOT NULL")).isEqualTo(if (action == "resume" && case != "no game") 1 else 0)
    }

    private fun perMove() = nothingSettings(mode = TimeControlMode.PER_MOVE, moveSeconds = 30)

    companion object {
        @JvmStatic
        fun rejections(): Stream<Arguments> = listOf("pause", "resume").flatMap { action ->
            // Снять паузу пытаются с партии на паузе.
            val running = RoomSetup { roomWith(ALICE, BOB, started = true).also { if (action == "resume") pause(ALICE, it) } }
            listOf(
                arguments(action, "not the owner", running, BOB, HttpStatus.FORBIDDEN, "NOT_ROOM_OWNER"),
                arguments(action, "not in the room", running, EVE, HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER"),
                arguments(action, "no game", RoomSetup { roomWith(ALICE, BOB) }, ALICE, HttpStatus.CONFLICT, "ROOM_NOT_IN_GAME"),
            )
        }.stream()
    }
}
