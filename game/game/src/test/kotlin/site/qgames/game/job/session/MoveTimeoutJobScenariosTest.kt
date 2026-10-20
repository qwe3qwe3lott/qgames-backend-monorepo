package site.qgames.game.job.session

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.room.RoomStatus
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import java.time.Instant
import java.util.UUID

/**
 * Истечение времени хода: дедлайн сдвигается в прошлое через SQL, джоб вызывается напрямую (по расписанию он в тестах
 * выключен). В «игре в ничто» истечение времени — автоматический PASS игрока, чьё время вышло.
 */
class MoveTimeoutJobScenariosTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var moveTimeoutJob: MoveTimeoutJob

    @Test
    fun `expired move is passed automatically and the next player gets a new deadline`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove(seconds = 30))
        expireMove(roomId)

        val before = Instant.now()
        val processed = moveTimeoutJob.run()
        val after = Instant.now()

        assertThat(processed).isEqualTo(1)
        val room = room(roomId, ALICE)
        val session = room.session!!
        assertThat(room.status).isEqualTo(RoomStatus.IN_GAME)
        assertThat(session.version).isEqualTo(1)
        assertThat(session.state).containsEntry("currentPlayer", BOB.toString())
        assertThat(session.moveDeadline).isBetween(before.plusSeconds(30).minusMillis(1), after.plusSeconds(30))
        val logged = jdbcClient.sql("SELECT seq, player_id, move::text AS move FROM room_session_move").query().singleRow()
        assertThat(logged).containsEntry("seq", 1L).containsEntry("player_id", ALICE).containsEntry("move", """{"type": "TIMEOUT"}""")

        // Следующий ход — уже обычный ход Боба.
        assertThat(pass(BOB, roomId).session!!.version).isEqualTo(2)
    }

    @Test
    fun `timeout of the last move ends the game and frees the room`() {
        val roomId = roomWith(ALICE, started = true, settings = perMove(seconds = 10, rounds = 1))
        expireMove(roomId)

        assertThat(moveTimeoutJob.run()).isEqualTo(1)

        val room = room(roomId, ALICE)
        assertThat(room.status).isEqualTo(RoomStatus.WAITING)
        assertThat(room.session!!.status).isEqualTo(SessionStatus.FINISHED)
        assertThat(room.session!!.moveDeadline).isNull()
        assertThat(countRows("room_session WHERE move_deadline IS NULL AND finished_at IS NOT NULL")).isEqualTo(1)
        assertThat(countRows("room WHERE current_session_id IS NULL AND status = 'WAITING'")).isEqualTo(1)
    }

    @Test
    fun `games without an expired deadline are left alone`() {
        val withoutTimer = roomWith(ALICE, started = true)
        val notExpired = roomWith(BOB, started = true, settings = perMove(seconds = 600))
        val cancelled = roomWith(ALICE, BOB, started = true, settings = perMove(seconds = 30))
        cancel(ALICE, cancelled)
        jdbcClient.sql("UPDATE room_session SET move_deadline = now() - interval '5 seconds' WHERE room_id = :id")
            .param("id", cancelled)
            .update()

        assertThat(moveTimeoutJob.run()).isZero()

        assertThat(countRows("room_session_move")).isZero()
        assertThat(room(withoutTimer, ALICE).session!!.moveDeadline).isNull()
        assertThat(room(notExpired, BOB).session!!.version).isZero()
    }

    @Test
    fun `timeout is not applied twice`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = perMove(seconds = 30))
        expireMove(roomId)

        assertThat(moveTimeoutJob.run()).isEqualTo(1)
        assertThat(moveTimeoutJob.run()).isZero()

        assertThat(countRows("room_session_move")).isEqualTo(1)
    }

    private fun perMove(seconds: Int, rounds: Int = 3) =
        nothingSettings(rounds = rounds, mode = TimeControlMode.PER_MOVE, moveSeconds = seconds)

    private fun expireMove(roomId: UUID) {
        jdbcClient.sql("UPDATE room_session SET move_deadline = now() - interval '5 seconds' WHERE room_id = :id AND status = 'IN_PROGRESS'")
            .param("id", roomId)
            .update()
    }
}
