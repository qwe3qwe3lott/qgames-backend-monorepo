package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.RoomRole
import site.qgames.game.api.dto.room.RoomSettingsChangedDto
import site.qgames.game.api.dto.room.RoomUsersDto
import site.qgames.game.job.game.NotPublishedGameCleanupJob
import site.qgames.game.job.room.RoomConnectionHeartbeatJob
import site.qgames.game.model.room.RoomEventType
import site.qgames.game.publisher.room.RoomEventPublisher
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.game.table.room.RoomMemberTable
import site.qgames.game.table.room.RoomTable
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Поток событий комнаты (SSE): первое сообщение — снапшот, дальше события из Postgres NOTIFY, только после
 * коммита и в порядке коммитов. Маркер подключения отсекает события, которые уже есть в снапшоте.
 */
class RoomEventsScenariosTest : GameIntegrationTest() {

    @Autowired
    private lateinit var roomEventPublisher: RoomEventPublisher

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var notPublishedGameCleanupJob: NotPublishedGameCleanupJob

    @Autowired
    private lateinit var roomConnectionHeartbeatJob: RoomConnectionHeartbeatJob

    /** Поток не кешируется и не буферизуется прокси. */
    @Test
    fun `stream is not cached or buffered by proxies`() {
        val roomId = roomWith(ALICE)

        val stream = openStream(BOB, roomId)

        assertThat(streams(BOB, roomId)).hasSize(1)
        assertThat(stream.response.getHeader("Cache-Control")).isEqualTo("no-cache")
        assertThat(stream.response.getHeader("X-Accel-Buffering")).isEqualTo("no")
    }

    @Test
    fun `first message is the snapshot of the room for the viewer`() {
        val roomId = roomWith(ALICE, started = true)

        val stream = openStream(BOB, roomId)

        val (type, data) = awaitEvents(stream, 1).first()
        assertThat(type).isEqualTo("snapshot")
        val snapshot = jsonMapper.readValue(data, RoomResponse::class.java)
        val current = room(roomId, BOB)
        assertThat(snapshot.copy(serverTime = current.serverTime)).isEqualTo(current)
        assertThat(snapshot.members.single { it.userId == BOB }.role).isEqualTo(RoomRole.SPECTATOR)
        assertThat(stream.response.contentType).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)
    }

    @Test
    fun `room changes reach the subscriber in commit order`() {
        val roomId = roomWith(ALICE)
        val aliceStream = openStream(ALICE, roomId)
        awaitEvents(aliceStream, 1)
        join(BOB, roomId)

        changeRole(BOB, roomId, RoomRole.SPECTATOR)
        updateSettingsRequest(ALICE, roomId, nothingSettings(rounds = 7)).andExpect { status { isOk() } }
        changeRole(BOB, roomId, RoomRole.PLAYER)
        start(ALICE, roomId)

        val events = awaitEvents(aliceStream, 6)
        assertThat(events.map { it.first }).containsExactly(
            "snapshot", "users-changed", "users-changed", "settings-changed", "users-changed", "session-changed",
        )
        val joined = jsonMapper.readValue(events[1].second, RoomUsersDto::class.java)
        assertThat(joined.ownerId).isEqualTo(ALICE)
        assertThat(joined.members.map { it.userId to it.role }).containsExactly(ALICE to RoomRole.PLAYER, BOB to RoomRole.PLAYER)
        assertThat(jsonMapper.readValue(events[2].second, RoomUsersDto::class.java).members.single { it.userId == BOB }.role)
            .isEqualTo(RoomRole.SPECTATOR)
        assertThat(jsonMapper.readValue(events[3].second, RoomSettingsChangedDto::class.java).settings)
            .isEqualTo(nothingSettings(rounds = 7))
        assertThat(events[5].second).isEqualTo("{}")
    }

    @Test
    fun `events reach all subscribers after commit and never after rollback`() {
        val roomId = roomWith(ALICE, BOB)
        val streams = listOf(openStream(ALICE, roomId), openStream(BOB, roomId))
        streams.forEach { awaitEvents(it, 1) }

        transactionTemplate.executeWithoutResult { status ->
            roomEventPublisher.publish(roomId, RoomEventType.SESSION_CHANGED)
            status.setRollbackOnly()
        }
        transactionTemplate.executeWithoutResult { roomEventPublisher.publish(roomId, RoomEventType.SETTINGS_CHANGED) }

        streams.forEach { stream ->
            assertThat(awaitEvents(stream, 2).map { it.first }).containsExactly("snapshot", "settings-changed")
        }
    }

    @Test
    fun `events committed before the marker are already in the snapshot and are not repeated`() {
        val roomId = roomWith(ALICE, BOB)
        val carolStream = AtomicReference<MvcResult>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()

        // Изменение держит блокировку комнаты, пока подписчик Карол уже зарегистрирован, а её подключение ждёт
        // блокировку: уведомление об изменении придёт подписчику раньше его маркера.
        transactionTemplate.executeWithoutResult {
            RoomTable.selectAll().where { RoomTable.id eq roomId }.forUpdate().single()
            RoomMemberTable.update({ (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq BOB) }) {
                it[role] = RoomRole.SPECTATOR.name
            }
            roomEventPublisher.publish(roomId, RoomEventType.USERS_CHANGED)
            executor.submit { carolStream.set(openStream(CAROL, roomId)) }
            // Активность других соединений смотрим из отдельной транзакции: внутри транзакции pg_stat_activity
            // не обновляется.
            awaitUntil("connection waits for the room lock") { executor.submit<Long> { lockWaiters() }.get() > 0 }
        }
        awaitUntil("stream is opened") { carolStream.get()?.let { events(it).isNotEmpty() } == true }
        val stream = carolStream.get()
        val snapshot = jsonMapper.readValue(events(stream).first().second, RoomResponse::class.java)
        assertThat(snapshot.members.single { it.userId == BOB }.role).isEqualTo(RoomRole.SPECTATOR)

        updateSettingsRequest(ALICE, roomId, nothingSettings(rounds = 2)).andExpect { status { isOk() } }

        assertThat(awaitEvents(stream, 2).map { it.first }).containsExactly("snapshot", "settings-changed")
        executor.shutdown()
    }

    @Test
    fun `nickname change reaches the rooms of the user`() {
        val roomId = roomWith(ALICE, BOB)
        val stream = openStream(ALICE, roomId)
        awaitEvents(stream, 1)

        updateProfile(BOB, "Bobby", Avatar.DOG)

        val (type, data) = awaitEvents(stream, 2)[1]
        assertThat(type).isEqualTo("users-changed")
        assertThat(jsonMapper.readValue(data, RoomUsersDto::class.java).members.single { it.userId == BOB }.nickname)
            .isEqualTo("Bobby")
    }

    @Test
    fun `deleted room sends the last event and closes the streams`() {
        val roomId = roomWith(ALICE, BOB)
        val stream = openStream(BOB, roomId)
        awaitEvents(stream, 1)
        setPublishStatus("nothing", "NOT_PUBLISHED")

        notPublishedGameCleanupJob.run()

        assertThat(awaitEvents(stream, 2).map { it.first }).containsExactly("snapshot", "room-deleted")
        awaitUntil("streams are closed") { roomStreamRegistry.subscribers(roomId).isEmpty() }
    }

    @Test
    fun `heartbeat pings the streams`() {
        val roomId = roomWith(ALICE)
        val stream = openStream(ALICE, roomId)
        awaitEvents(stream, 1)

        roomConnectionHeartbeatJob.run()

        awaitUntil("ping in the stream") { stream.response.getContentAsString(Charsets.UTF_8).contains(": ping\n") }
    }

    @Test
    fun `errors before the stream starts are problem details`() {
        mockMvc.get("/api/rooms/${UUID.randomUUID()}/events") {
            asUser(ALICE)
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("ROOM_NOT_FOUND") }
        }
        assertThat(roomStreamRegistry.all()).isEmpty()
    }

    private fun lockWaiters(): Long = count(PgStatActivity) { PgStatActivity.waitEventType eq "Lock" }

    /** Системное представление PostgreSQL с активностью соединений — только для теста ожидания блокировки. */
    private object PgStatActivity : Table("pg_stat_activity") {
        val waitEventType = varchar("wait_event_type", 64).nullable()
    }
}
