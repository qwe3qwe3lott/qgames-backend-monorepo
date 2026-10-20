package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.room.CreateRoomRequest
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.job.room.RoomConnectionCleanupJob
import site.qgames.game.job.room.RoomConnectionHeartbeatJob
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Присутствие: открытый поток комнаты и есть нахождение в ней. Подключение входит в комнату, закрытие последнего потока
 * пользователя ставит срок выхода, подключения упавших инстансов истекают по аренде.
 */
class RoomPresenceScenariosTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var roomConnectionCleanupJob: RoomConnectionCleanupJob

    @Autowired
    private lateinit var roomConnectionHeartbeatJob: RoomConnectionHeartbeatJob

    @Test
    fun `connecting to the stream joins the room`() {
        val roomId = roomWith(ALICE)

        openStream(BOB, roomId)

        val bob = room(roomId, BOB).members.single { it.userId == BOB }
        assertThat(bob.role).isEqualTo(RoomRole.PLAYER)
        assertThat(bob.leaving).isFalse()
        assertThat(connections(roomId, BOB)).isEqualTo(1)
    }

    @Test
    fun `member stays while at least one stream is open`() {
        val roomId = roomWith(ALICE)
        openStream(BOB, roomId)
        openStream(BOB, roomId)
        val (first, second) = streams(BOB, roomId)

        first.close()

        assertThat(leaveDeadline(roomId, BOB)).isNull()
        assertThat(connections(roomId, BOB)).isEqualTo(1)

        second.close()

        assertThat(leaveDeadline(roomId, BOB)).isNotNull()
        assertThat(connections(roomId, BOB)).isZero()
        assertThat(room(roomId, ALICE).members.single { it.userId == BOB }.leaving).isTrue()
    }

    @Test
    fun `reconnecting within the deadline cancels the leave`() {
        val roomId = roomWith(ALICE, BOB)
        leave(BOB, roomId)

        openStream(BOB, roomId)

        assertThat(leaveDeadline(roomId, BOB)).isNull()
    }

    @Test
    fun `connection with an expired lease is removed and its user leaves`() {
        val roomId = roomWith(ALICE, BOB)
        expireConnections(roomId, BOB)

        assertThat(roomConnectionCleanupJob.run()).isEqualTo(1)

        assertThat(connections(roomId, BOB)).isZero()
        assertThat(leaveDeadline(roomId, BOB)).isNotNull()
        assertThat(leaveDeadline(roomId, ALICE)).isNull()
        assertThat(roomConnectionCleanupJob.run()).isZero()
    }

    @Test
    fun `heartbeat renews the lease of live streams`() {
        val roomId = roomWith(ALICE)
        expireConnections(roomId, ALICE)

        assertThat(roomConnectionHeartbeatJob.run()).isEqualTo(1)

        assertThat(roomConnectionCleanupJob.run()).isZero()
        assertThat(connectionExpiresAt(roomId, ALICE)).isAfter(Instant.now().plus(Duration.ofSeconds(15)))
    }

    /** Подключение удалил cleanup (продление задержалось дольше аренды): поток закрывается, клиент войдёт заново. */
    @Test
    fun `stream whose connection is gone is closed by the heartbeat`() {
        val roomId = roomWith(ALICE, BOB)
        expireConnections(roomId, BOB)
        roomConnectionCleanupJob.run()
        assertThat(streams(BOB, roomId)).hasSize(1)

        assertThat(roomConnectionHeartbeatJob.run()).isEqualTo(1)

        assertThat(streams(BOB, roomId)).isEmpty()
        assertThat(streams(ALICE, roomId)).hasSize(1)
        assertThat(leaveDeadline(roomId, BOB)).isNotNull()
        openStream(BOB, roomId)
        assertThat(leaveDeadline(roomId, BOB)).isNull()
    }

    @Test
    fun `creator who never connects is removed after the owner grace`() {
        val before = Instant.now()
        val created: RoomResponse = createRoomRequest(ALICE, CreateRoomRequest(nothingSettings()))
            .andExpect { status { isOk() } }
            .body()
        val roomId = created.id

        assertThat(created.members.single().leaving).isTrue()
        assertThat(leaveDeadline(roomId, ALICE)).isBetween(before.plusSeconds(15).minusMillis(1), Instant.now().plusSeconds(15))
        expireLeave(roomId, ALICE)
        roomLeaveJob.run()

        val room = room(roomId, BOB)
        assertThat(room.members).isEmpty()
        assertThat(room.ownerId).isNull()
    }

    @Test
    fun `creator who connects stays in the room`() {
        val roomId = createRoom(ALICE).id

        assertThat(leaveDeadline(roomId, ALICE)).isNull()
        assertThat(room(roomId, ALICE).members.single().owner).isTrue()
    }

    private fun connections(roomId: UUID, user: UUID): Long =
        countRows("room_connection WHERE room_id = :roomId AND user_id = :userId", "roomId" to roomId, "userId" to user)

    private fun expireConnections(roomId: UUID, user: UUID) {
        jdbcClient.sql("UPDATE room_connection SET expires_at = now() - interval '1 second' WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", user)
            .update()
    }

    private fun connectionExpiresAt(roomId: UUID, user: UUID): Instant =
        jdbcClient.sql("SELECT expires_at FROM room_connection WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", user)
            .query { rs, _ -> rs.getTimestamp("expires_at").toInstant() }
            .single()
}
