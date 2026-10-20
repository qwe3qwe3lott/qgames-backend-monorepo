package site.qgames.game.job.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.job.session.MoveTimeoutJob
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL

/**
 * Принудительное отключение игры: ForceDisabledGameCleanupJob отменяет партии и удаляет комнаты игры, а остальные
 * джобы её комнаты не трогают. Джобы вызываются напрямую (по расписанию в тестах они выключены).
 */
class ForceDisabledGameCleanupJobScenariosTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var forceDisabledGameCleanupJob: ForceDisabledGameCleanupJob

    @Autowired
    private lateinit var moveTimeoutJob: MoveTimeoutJob

    @Test
    fun `rooms of a force disabled game are deleted with their games`() {
        val running = roomWith(ALICE, BOB, started = true)
        pass(ALICE, running)
        roomWith(CAROL)
        setPublishStatus("nothing", "FORCE_DISABLED")

        assertThat(forceDisabledGameCleanupJob.run()).isEqualTo(2)

        assertThat(countRows("room")).isZero()
        assertThat(countRows("room_member")).isZero()
        assertThat(countRows("room_session")).isZero()
        assertThat(countRows("room_session_player")).isZero()
        assertThat(countRows("room_session_move")).isZero()
        assertThat(forceDisabledGameCleanupJob.run()).isZero()
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("PUBLISHED", "HIDDEN", "DISABLED", "NOT_PUBLISHED")
    fun `rooms of other games are left alone`(status: String) {
        val roomId = roomWith(ALICE, BOB, started = true)
        setPublishStatus("nothing", status)

        assertThat(forceDisabledGameCleanupJob.run()).isZero()

        assertThat(countRows("room WHERE id = :id", "id" to roomId)).isEqualTo(1)
        assertThat(countRows("room_session WHERE status = 'IN_PROGRESS'")).isEqualTo(1)
    }

    @Test
    fun `move timeout skips games of a force disabled game`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(mode = TimeControlMode.PER_MOVE, moveSeconds = 30))
        jdbcClient.sql("UPDATE room_session SET move_deadline = now() - interval '5 seconds' WHERE room_id = :id")
            .param("id", roomId)
            .update()
        setPublishStatus("nothing", "FORCE_DISABLED")

        assertThat(moveTimeoutJob.run()).isZero()

        assertThat(countRows("room_session_move")).isZero()
    }

    @Test
    fun `expired leave is not processed in a room of a force disabled game`() {
        val roomId = roomWith(ALICE, BOB)
        leave(BOB, roomId)
        expireLeave(roomId, BOB)
        setPublishStatus("nothing", "FORCE_DISABLED")

        assertThat(roomLeaveJob.run()).isZero()

        assertThat(countRows("room_member WHERE room_id = :id", "id" to roomId)).isEqualTo(2)
    }
}
