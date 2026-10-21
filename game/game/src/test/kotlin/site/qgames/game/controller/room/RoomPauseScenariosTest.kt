package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
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
import site.qgames.games.api.timer.TimerStatus
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.stream.Stream

/**
 * Пауза — действие таймера партии: ставит и снимает владелец (POST …/session/pause и …/resume, 204). На паузе ходы
 * запрещены, дедлайн хода и сроки возвращения игроков партии заморожены, отмена работает. У партии без таймера паузы нет.
 */
class RoomPauseScenariosTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var moveTimeoutJob: MoveTimeoutJob

    /** Готовит комнату для сценария и возвращает её id. */
    fun interface RoomSetup {
        fun RoomPauseScenariosTest.prepare(): UUID
    }

    @Test
    fun `owner pauses the timer, moves are rejected and the deadline is kept`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove())
        val deadline = fullState(roomId).state.timer!!.deadline

        pause(ALICE, roomId)

        val timer = fullState(roomId).state.timer!!
        assertThat(timer.status).isEqualTo(TimerStatus.PAUSED)
        assertThat(timer.pausedAt).isNotNull()
        assertThat(timer.deadline).isEqualTo(deadline)
        assertThat(journal(roomId).map { it.second }).containsExactly("session-started", "game-paused")
        assertThat(room(roomId, ALICE).status).isEqualTo(RoomStatus.IN_GAME)
        // Проекция таймера: на паузе дедлайна для MoveTimeoutJob нет.
        assertThat(countRows("room_session WHERE move_deadline IS NULL")).isEqualTo(1)
        moveRequest(ALICE, roomId).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SESSION_PAUSED") }
        }
        assertThat(journal(roomId)).hasSize(2)
    }

    @Test
    fun `repeated pause and resume without a pause change nothing`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove())

        resume(ALICE, roomId)
        pause(ALICE, roomId)
        val pausedAt = fullState(roomId).state.timer!!.pausedAt
        pause(ALICE, roomId)

        assertThat(fullState(roomId).state.timer!!.pausedAt).isEqualTo(pausedAt)
        assertThat(journal(roomId).map { it.second }).containsExactly("session-started", "game-paused")
    }

    @Test
    fun `game without a timer cannot be paused`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        listOf("pause", "resume").forEach { action ->
            call(HttpMethod.POST, "/api/rooms/$roomId/session/$action", ALICE).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("SESSION_NOT_PAUSABLE") }
            }
        }
        assertThat(journal(roomId)).hasSize(1)
    }

    @Test
    fun `move timeout does not fire on a paused game`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove())
        pause(ALICE, roomId)
        jdbcClient.sql("UPDATE room_session SET move_deadline = now() - interval '5 seconds' WHERE room_id = :id")
            .param("id", roomId)
            .update()

        moveTimeoutJob.run()

        assertThat(journal(roomId).map { it.second }).doesNotContain("player-passed")
    }

    @Test
    fun `players of a paused game are not removed, other leavers are`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove()).also { join(CAROL, it) }
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
        // Пауза длится минуту: её начало в журнале сдвинуто на минуту назад.
        jdbcClient.sql("UPDATE room_session SET move_deadline = NULL WHERE room_id = :id").param("id", roomId).update()
        val pausedAt = Instant.now().minusSeconds(60)
        appendPause(roomId, pausedAt)
        val moveDeadline = fullState(roomId).state.timer!!.deadline!!
        val bobDeadline = leaveDeadline(roomId, BOB)!!
        val carolDeadline = leaveDeadline(roomId, CAROL)

        val before = Instant.now()
        resume(ALICE, roomId)
        val after = Instant.now()

        val minShift = Duration.between(pausedAt, before).minusMillis(1)
        val maxShift = Duration.between(pausedAt, after).plusMillis(1)
        val timer = fullState(roomId).state.timer!!
        assertThat(timer.status).isEqualTo(TimerStatus.RUNNING)
        assertThat(timer.pausedAt).isNull()
        assertThat(timer.deadline).isBetween(moveDeadline.plus(minShift), moveDeadline.plus(maxShift))
        assertThat(storedDeadline(roomId)).isCloseTo(timer.deadline, within(1, ChronoUnit.MILLIS))
        assertThat(leaveDeadline(roomId, BOB)).isBetween(bobDeadline.plus(minShift), bobDeadline.plus(maxShift))
        assertThat(leaveDeadline(roomId, CAROL)).isEqualTo(carolDeadline)
        // Ходы снова разрешены.
        pass(ALICE, roomId)
    }

    @Test
    fun `owner cancels a paused game`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove())
        pause(ALICE, roomId)

        cancel(ALICE, roomId)

        val room = room(roomId, ALICE)
        assertThat(room.status).isEqualTo(RoomStatus.WAITING)
        assertThat(room.session!!.status).isEqualTo(SessionStatus.CANCELLED)
        assertThat(countRows("room_session WHERE move_deadline IS NULL AND status = 'CANCELLED'")).isEqualTo(1)
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
        val eventsBefore = countRows("room_session_event")

        call(HttpMethod.POST, "/api/rooms/$roomId/session/$action", user).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            jsonPath("$.code") { value(expectedCode) }
        }
        assertThat(countRows("room_session_event")).isEqualTo(eventsBefore)
    }

    /** Пишет в журнал паузу таймера с момента [pausedAt] — как будто владелец поставил её тогда. */
    private fun appendPause(roomId: UUID, pausedAt: Instant) {
        val data = jsonMapper.writeValueAsString(
            mapOf("path" to listOf("state", "timer"), "value" to mapOf("status" to TimerStatus.PAUSED, "pausedAt" to pausedAt)),
        )
        jdbcClient.sql(
            """
            INSERT INTO room_session_event (session_id, seq, method, code, data)
            SELECT id, last_seq + 1, 'PATCH', 'game-paused', CAST(:data AS jsonb) FROM room_session WHERE id = :id
            """,
        )
            .param("id", sessionId(roomId))
            .param("data", data)
            .update()
        jdbcClient.sql("UPDATE room_session SET last_seq = last_seq + 1 WHERE id = :id").param("id", sessionId(roomId)).update()
    }

    private fun storedDeadline(roomId: UUID): Instant? =
        jdbcClient.sql("SELECT move_deadline FROM room_session WHERE room_id = :id")
            .param("id", roomId)
            .query { rs, _ -> rs.getTimestamp("move_deadline")?.toInstant() }
            .list()
            .single()

    private fun perMove() = nothingSettings(mode = TimeControlMode.PER_MOVE, moveSeconds = 30)

    companion object {
        @JvmStatic
        fun rejections(): Stream<Arguments> = listOf("pause", "resume").flatMap { action ->
            val running = RoomSetup {
                roomWith(ALICE, BOB, started = true, settings = nothingSettings(mode = TimeControlMode.PER_MOVE, moveSeconds = 30))
                    .also { if (action == "resume") pause(ALICE, it) }
            }
            listOf(
                arguments(action, "not the owner", running, BOB, HttpStatus.FORBIDDEN, "NOT_ROOM_OWNER"),
                arguments(action, "not in the room", running, EVE, HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER"),
                arguments(action, "no game", RoomSetup { roomWith(ALICE, BOB) }, ALICE, HttpStatus.CONFLICT, "ROOM_NOT_IN_GAME"),
            )
        }.stream()
    }
}
