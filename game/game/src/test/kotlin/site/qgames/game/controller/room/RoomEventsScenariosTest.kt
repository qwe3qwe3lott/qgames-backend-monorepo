package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import site.qgames.common.web.header.QgamesHeaders
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.api.RoomEventsApi
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.gatewayapi.dto.room.RoomSettingsChangedDto
import site.qgames.game.gatewayapi.dto.room.RoomUsersDto
import site.qgames.game.job.game.NotPublishedGameCleanupJob
import site.qgames.game.job.room.RoomConnectionHeartbeatJob
import site.qgames.game.model.room.RoomEventType
import site.qgames.game.publisher.room.RoomEventPublisher
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.userauth.asyncapi.event.Avatar
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Поток событий комнаты (SSE): первое сообщение — снапшот, дальше события из шины (Postgres NOTIFY), только после
 * коммита. Маркер подключения отсекает события, которые уже есть в снапшоте.
 */
class RoomEventsScenariosTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var roomEventPublisher: RoomEventPublisher

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var notPublishedGameCleanupJob: NotPublishedGameCleanupJob

    @Autowired
    private lateinit var roomConnectionHeartbeatJob: RoomConnectionHeartbeatJob

    /** Контроллер game объявляет маршрут из константы контракта, по которой gateway строит клиента. */
    @Test
    fun `stream route matches the gateway contract`() {
        assertThat(RoomEventsApi.PATH).isEqualTo("/api/rooms/{roomId}/events")
        val roomId = roomWith(ALICE)

        openStream(BOB, roomId)

        assertThat(streams(BOB, roomId)).hasSize(1)
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

        // Изменение держит блокировку комнаты, пока подписчик Карол уже зарегистрирован, а её подключение ждёт блокировку:
        // уведомление об изменении придёт подписчику раньше его маркера.
        transactionTemplate.executeWithoutResult {
            jdbcClient.sql("SELECT id FROM room WHERE id = :id FOR UPDATE").param("id", roomId).query().listOfRows()
            jdbcClient.sql("UPDATE room_member SET role = 'SPECTATOR' WHERE room_id = :id AND user_id = :user")
                .param("id", roomId)
                .param("user", BOB)
                .update()
            roomEventPublisher.publish(roomId, RoomEventType.USERS_CHANGED)
            executor.submit { carolStream.set(openStream(CAROL, roomId)) }
            awaitUntil("connection waits for the room lock") { lockWaiters() > 0 }
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

        publishProfile(BOB, "Bobby", Avatar.AVATAR_DOG, version = 1)

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
            header(QgamesHeaders.USER_ID, ALICE.toString())
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("ROOM_NOT_FOUND") }
        }
        assertThat(roomStreamRegistry.all()).isEmpty()
    }

    private fun lockWaiters(): Int =
        jdbcClient.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()")
            .query(Int::class.java)
            .single()
}
