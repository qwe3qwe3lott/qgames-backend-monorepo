package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.RoomRole
import site.qgames.game.job.room.RoomConnectionCleanupJob
import site.qgames.game.job.room.RoomConnectionHeartbeatJob
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import site.qgames.game.table.room.RoomConnectionTable

/**
 * Присутствие: открытый поток комнаты и есть нахождение в ней. Подключение входит в комнату, закрытие последнего потока
 * пользователя выводит из комнаты, подключения упавшего процесса истекают по аренде.
 */
class RoomPresenceScenariosTest : GameIntegrationTest() {

    @Autowired
    private lateinit var roomConnectionCleanupJob: RoomConnectionCleanupJob

    @Autowired
    private lateinit var roomConnectionHeartbeatJob: RoomConnectionHeartbeatJob

    @Test
    fun `connecting to the stream joins the room`() {
        val roomId = roomWith(ALICE)

        openStream(BOB, roomId)

        val bob = room(roomId, BOB).members.single { it.id == BOB }
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

        assertThat(isMember(roomId, BOB)).isTrue()
        assertThat(connections(roomId, BOB)).isEqualTo(1)

        second.close()

        assertThat(isMember(roomId, BOB)).isFalse()
        assertThat(connections(roomId, BOB)).isZero()
        assertThat(room(roomId, ALICE).members.map { it.id }).containsExactly(ALICE)
    }

    @Test
    fun `reconnecting player of the running game is present again`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        leave(BOB, roomId)

        openStream(BOB, roomId)

        assertThat(leftAt(roomId, BOB)).isNull()
    }

    @Test
    fun `connection with an expired lease is removed and its user leaves`() {
        val roomId = roomWith(ALICE, BOB)
        expireConnections(roomId, BOB)

        assertThat(roomConnectionCleanupJob.run()).isEqualTo(1)

        assertThat(connections(roomId, BOB)).isZero()
        assertThat(isMember(roomId, BOB)).isFalse()
        assertThat(isMember(roomId, ALICE)).isTrue()
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
        assertThat(isMember(roomId, BOB)).isFalse()
        openStream(BOB, roomId)
        assertThat(isMember(roomId, BOB)).isTrue()
    }

    private fun connections(roomId: UUID, user: UUID): Long =
        count(RoomConnectionTable) { connection(roomId, user) }

    private fun expireConnections(roomId: UUID, user: UUID) {
        db { RoomConnectionTable.update({ connection(roomId, user) }) { it[expiresAt] = Instant.now().minusSeconds(1) } }
    }

    private fun connectionExpiresAt(roomId: UUID, user: UUID): Instant =
        db { RoomConnectionTable.selectAll().where { connection(roomId, user) }.single()[RoomConnectionTable.expiresAt] }

    private fun connection(roomId: UUID, user: UUID) = (RoomConnectionTable.roomId eq roomId) and (RoomConnectionTable.userId eq user)
}
