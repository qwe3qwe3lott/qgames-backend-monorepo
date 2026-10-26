package site.qgames.game.controller.room

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.stream.Stream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.RoomStatus
import site.qgames.game.api.dto.room.SessionStatus
import site.qgames.game.job.session.MoveTimeoutJob
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.support.TestData.EVE
import site.qgames.game.table.session.RoomSessionEventTable
import site.qgames.game.table.session.RoomSessionTable
import site.qgames.games.core.TimerStatus

/**
 * Пауза — действие таймера партии: ставит и снимает владелец (POST …/session/pause и …/resume, 204). На паузе ходы
 * запрещены, дедлайн хода заморожен, отмена работает. У партии без таймера паузы нет.
 */
class RoomPauseScenariosTest : GameIntegrationTest() {

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
        assertThat(count(RoomSessionTable) { RoomSessionTable.moveDeadline.isNull() }).isEqualTo(1)
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
        db { RoomSessionTable.update({ RoomSessionTable.roomId eq roomId }) { it[moveDeadline] = Instant.now().minusSeconds(5) } }

        moveTimeoutJob.run()

        assertThat(journal(roomId).map { it.second }).doesNotContain("player-passed")
    }

    @Test
    fun `owner leaving a paused game passes ownership at once`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove())
        pause(ALICE, roomId)

        leave(ALICE, roomId)

        assertThat(room(roomId, BOB).ownerId).isEqualTo(BOB)
        resume(BOB, roomId)
    }

    @Test
    fun `players of a paused game stay absent, spectators are removed`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove()).also { join(CAROL, it) }
        pause(ALICE, roomId)
        leave(BOB, roomId)
        leave(CAROL, roomId)

        val room = room(roomId, ALICE)
        assertThat(room.members.map { it.userId to it.leaving }).containsExactly(ALICE to false, BOB to true)
    }

    @Test
    fun `resume shifts the move deadline by the pause length`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove())
        // Пауза длится минуту: её начало в журнале сдвинуто на минуту назад.
        db { RoomSessionTable.update({ RoomSessionTable.roomId eq roomId }) { it[moveDeadline] = null } }
        val pausedAt = Instant.now().minusSeconds(60)
        appendPause(roomId, pausedAt)
        val moveDeadline = fullState(roomId).state.timer!!.deadline!!

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
        assertThat(count(RoomSessionTable) { RoomSessionTable.moveDeadline.isNull() and (RoomSessionTable.status eq "CANCELLED") }).isEqualTo(1)
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
        val eventsBefore = count(RoomSessionEventTable)

        call(HttpMethod.POST, "/api/rooms/$roomId/session/$action", user).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            jsonPath("$.code") { value(expectedCode) }
        }
        assertThat(count(RoomSessionEventTable)).isEqualTo(eventsBefore)
    }

    /** Пишет в журнал паузу таймера с момента [pausedAt] — как будто владелец поставил её тогда. */
    private fun appendPause(roomId: UUID, pausedAt: Instant) {
        val data = jsonMapper.writeValueAsString(
            mapOf("path" to listOf("state", "timer"), "value" to mapOf("status" to TimerStatus.PAUSED, "pausedAt" to pausedAt)),
        )
        val session = roomSessionRepository.findById(sessionId(roomId))!!
        val seq = session.lastSeq + 1
        db {
            RoomSessionEventTable.insert {
                it[sessionId] = session.id
                it[RoomSessionEventTable.seq] = seq
                it[method] = "PATCH"
                it[code] = "game-paused"
                it[RoomSessionEventTable.data] = data
                it[createdAt] = Instant.now()
            }
            RoomSessionTable.update({ RoomSessionTable.id eq session.id }) { it[lastSeq] = seq }
        }
    }

    private fun storedDeadline(roomId: UUID): Instant? =
        db { RoomSessionTable.selectAll().where { RoomSessionTable.roomId eq roomId }.single()[RoomSessionTable.moveDeadline] }

    private fun perMove() = nothingSettings(clockSeconds = 30)

    companion object {
        @JvmStatic
        fun rejections(): Stream<Arguments> = listOf("pause", "resume").flatMap { action ->
            val running = RoomSetup {
                roomWith(ALICE, BOB, started = true, settings = nothingSettings(clockSeconds = 30))
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
