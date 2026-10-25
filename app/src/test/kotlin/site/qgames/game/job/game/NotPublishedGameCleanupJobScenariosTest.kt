package site.qgames.game.job.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.TimeControlMode
import site.qgames.game.job.session.MoveTimeoutJob
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import site.qgames.game.table.room.RoomTable
import site.qgames.game.table.room.RoomMemberTable
import site.qgames.game.table.session.RoomSessionTable
import site.qgames.game.table.session.RoomSessionPlayerTable
import site.qgames.game.table.session.RoomSessionEventTable
import java.time.Instant

/**
 * Снятие игры с публикации (NOT_PUBLISHED): NotPublishedGameCleanupJob отменяет партии и удаляет комнаты игры, а
 * остальные джобы её комнаты не трогают. Джобы вызываются напрямую (по расписанию в тестах они выключены).
 */
class NotPublishedGameCleanupJobScenariosTest : GameIntegrationTest() {

    @Autowired
    private lateinit var notPublishedGameCleanupJob: NotPublishedGameCleanupJob

    @Autowired
    private lateinit var moveTimeoutJob: MoveTimeoutJob

    @Test
    fun `rooms of a not published game are deleted with their games`() {
        val running = roomWith(ALICE, BOB, started = true)
        pass(ALICE, running)
        roomWith(CAROL)
        setPublishStatus("nothing", "NOT_PUBLISHED")

        assertThat(notPublishedGameCleanupJob.run()).isEqualTo(2)

        assertThat(count(RoomTable)).isZero()
        assertThat(count(RoomMemberTable)).isZero()
        assertThat(count(RoomSessionTable)).isZero()
        assertThat(count(RoomSessionPlayerTable)).isZero()
        assertThat(count(RoomSessionEventTable)).isZero()
        assertThat(notPublishedGameCleanupJob.run()).isZero()
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("PUBLISHED", "HIDDEN")
    fun `rooms of other games are left alone`(status: String) {
        val roomId = roomWith(ALICE, BOB, started = true)
        setPublishStatus("nothing", status)

        assertThat(notPublishedGameCleanupJob.run()).isZero()

        assertThat(count(RoomTable) { RoomTable.id eq roomId }).isEqualTo(1)
        assertThat(count(RoomSessionTable) { RoomSessionTable.status eq "IN_PROGRESS" }).isEqualTo(1)
    }

    @Test
    fun `move timeout skips games of a not published game`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(mode = TimeControlMode.PER_MOVE, moveSeconds = 30))
        db { RoomSessionTable.update({ RoomSessionTable.roomId eq roomId }) { it[moveDeadline] = Instant.now().minusSeconds(5) } }
        setPublishStatus("nothing", "NOT_PUBLISHED")

        assertThat(moveTimeoutJob.run()).isZero()

        assertThat(count(RoomSessionEventTable) { RoomSessionEventTable.code eq "player-passed" }).isZero()
    }

    @Test
    fun `expired owner grace is not processed in a room of a not published game`() {
        val roomId = roomWith(ALICE, BOB)
        leave(ALICE, roomId)
        expireOwnerGrace(roomId)
        setPublishStatus("nothing", "NOT_PUBLISHED")

        assertThat(roomOwnerGraceJob.run()).isZero()

        assertThat(ownerGrace(roomId)?.first).isEqualTo(ALICE)
    }
}
