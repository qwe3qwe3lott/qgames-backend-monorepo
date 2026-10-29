package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import site.qgames.common.docpatch.DocumentPatch
import site.qgames.common.docpatch.PatchMethod
import site.qgames.common.docpatch.PatchOperation
import site.qgames.common.docpatch.PathSegment
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.RoomRole
import site.qgames.game.api.dto.room.RoomSnapshotDto
import site.qgames.game.api.dto.room.TransferOwnershipRequest
import site.qgames.game.api.dto.room.UpdateRoomSettingsRequest
import site.qgames.game.job.game.NotPublishedGameCleanupJob
import site.qgames.game.job.room.RoomConnectionHeartbeatJob
import site.qgames.game.model.room.RoomMemberDetailsModel
import site.qgames.game.model.room.RoomMemberModel
import site.qgames.game.model.user.UserAvatar
import site.qgames.game.model.user.UserProfileModel
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import site.qgames.game.model.room.RoomRole as RoomRoleModel

/**
 * Поток событий комнаты (SSE): первое сообщение — снапшот на seq, дальше события-патчи документа из Postgres NOTIFY
 * с непрерывным seq, только после коммита и в порядке коммитов. События с seq не новее снапшота отбрасываются.
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
    fun `first message is the snapshot of the room for the viewer at the last seq`() {
        val roomId = roomWith(ALICE, started = true)

        val stream = openStream(BOB, roomId)

        val (type, data) = awaitEvents(stream, 1).first()
        assertThat(type).isEqualTo("snapshot")
        val snapshot = jsonMapper.readValue(data, RoomSnapshotDto::class.java)
        val current = room(roomId, BOB)
        assertThat(snapshot.room).isEqualTo(current)
        assertThat(snapshot.room.members.single { it.id == BOB }.role).isEqualTo(RoomRole.SPECTATOR)
        assertThat(snapshot.seq).isEqualTo(eventSeq(roomId))
        assertThat(stream.response.contentType).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)
    }

    @Test
    fun `room changes reach the subscriber as patches in commit order`() {
        val roomId = roomWith(ALICE)
        val aliceStream = openStream(ALICE, roomId)
        val snapshotSeq = snapshot(aliceStream).seq

        join(BOB, roomId)
        changeRole(BOB, roomId, RoomRole.SPECTATOR)
        updateSettingsRequest(ALICE, roomId, UpdateRoomSettingsRequest(advanced = nothingSettings(rounds = 7)))
            .andExpect { status { isNoContent() } }
        changeRole(BOB, roomId, RoomRole.PLAYER)
        start(ALICE, roomId)

        val events = patches(aliceStream, 5)
        assertThat(events.map { it["code"] }).containsExactly(
            "member-joined", "role-changed", "settings-changed", "role-changed", "session-started",
        )
        assertThat(events.map { (it["seq"] as Number).toLong() }).containsExactly(
            snapshotSeq + 1, snapshotSeq + 2, snapshotSeq + 3, snapshotSeq + 4, snapshotSeq + 5,
        )
        assertThat(events.map { it["method"] }).containsExactly("PUT", "PATCH", "PUT", "PATCH", "PATCH")
        assertThat(value(events[0])).containsEntry("id", BOB.toString()).containsEntry("role", "PLAYER")
        assertThat(path(events[1])).isEqualTo(listOf("members", mapOf("id" to BOB.toString())))
        assertThat(value(events[1])).isEqualTo(mapOf("role" to "SPECTATOR"))
        assertThat(path(events[2])).isEqualTo(listOf("settings"))
        assertThat(value(events[2])["advanced"]).isEqualTo(jsonMapper.convertValue(nothingSettings(rounds = 7), Map::class.java))
        assertThat(value(events[3])).isEqualTo(mapOf("role" to "PLAYER"))
        assertThat(value(events[4])).containsEntry("status", "IN_GAME")
    }

    @Test
    fun `snapshot with the events applied in order equals the room`() {
        val roomId = roomWith(ALICE)
        val aliceStream = openStream(ALICE, roomId)
        val snapshot = snapshot(aliceStream)

        join(BOB, roomId)
        join(CAROL, roomId)
        changeRole(CAROL, roomId, RoomRole.SPECTATOR)
        updateSettingsRequest(ALICE, roomId, UpdateRoomSettingsRequest(advanced = nothingSettings(rounds = 3)))
            .andExpect { status { isNoContent() } }
        start(ALICE, roomId)
        call(HttpMethod.PUT, "/api/rooms/$roomId/owner", ALICE, TransferOwnershipRequest(CAROL)).andExpect { status { isNoContent() } }
        leave(BOB, roomId)
        awaitUntil("Bob is away") { leftAt(roomId, BOB) != null }
        updateProfile(CAROL, "Carolina", Avatar.DOG)
        cancel(CAROL, roomId)

        // member-joined ×2, role-changed, settings-changed, session-started, owner-changed, member-leaving,
        // profile-changed, session-ended, member-left
        val events = patches(aliceStream, 10)
        assertThat(events.map { it["code"] }).containsExactly(
            "member-joined", "member-joined", "role-changed", "settings-changed", "session-started", "owner-changed",
            "member-leaving", "profile-changed", "session-ended", "member-left",
        )
        val folded = DocumentPatch.fold(tree(snapshot.room), events.map(::operation))!!
        val current = room(roomId, ALICE)
        assertThat(folded).isEqualTo(tree(current))
    }

    @Test
    fun `events reach all subscribers after commit and never after rollback`() {
        val roomId = roomWith(ALICE, BOB)
        val streams = listOf(openStream(ALICE, roomId), openStream(BOB, roomId))
        val seq = streams.map { snapshot(it).seq }.max()

        transactionTemplate.executeWithoutResult { status ->
            lockRoom(roomId)
            roomEventPublisher.ownerChanged(roomId, BOB)
            status.setRollbackOnly()
        }
        transactionTemplate.executeWithoutResult {
            lockRoom(roomId)
            roomEventPublisher.ownerChanged(roomId, ALICE)
        }

        streams.forEach { stream ->
            val event = patches(stream, 1).single()
            assertThat(event["code"]).isEqualTo("owner-changed")
            assertThat(value(event)).isEqualTo(mapOf("ownerId" to ALICE.toString()))
            assertThat((event["seq"] as Number).toLong()).isEqualTo(seq + 1)
        }
    }

    @Test
    fun `events committed before the snapshot are already in it and are not repeated`() {
        val roomId = roomWith(ALICE, BOB)
        val carolStream = AtomicReference<MvcResult>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()

        // Изменение держит блокировку комнаты, пока подписчик Карол уже зарегистрирован, а её подключение ждёт
        // блокировку: уведомление об изменении придёт подписчику раньше снапшота.
        transactionTemplate.executeWithoutResult {
            lockRoom(roomId)
            RoomMemberTable.update({ (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq BOB) }) {
                it[role] = RoomRole.SPECTATOR.name
            }
            roomEventPublisher.roleChanged(roomId, BOB, RoomRoleModel.SPECTATOR)
            executor.submit { carolStream.set(openStream(CAROL, roomId)) }
            // Активность других соединений смотрим из отдельной транзакции: внутри транзакции pg_stat_activity
            // не обновляется.
            awaitUntil("connection waits for the room lock") { executor.submit<Long> { lockWaiters() }.get() > 0 }
        }
        awaitUntil("stream is opened") { carolStream.get()?.let { events(it).isNotEmpty() } == true }
        val stream = carolStream.get()
        val snapshot = snapshot(stream)
        assertThat(snapshot.room.members.single { it.id == BOB }.role).isEqualTo(RoomRole.SPECTATOR)
        assertThat(snapshot.room.members.map { it.id }).contains(CAROL)

        updateSettingsRequest(ALICE, roomId, UpdateRoomSettingsRequest(advanced = nothingSettings(rounds = 2)))
            .andExpect { status { isNoContent() } }

        val event = patches(stream, 1).single()
        assertThat(event["code"]).isEqualTo("settings-changed")
        assertThat((event["seq"] as Number).toLong()).isEqualTo(snapshot.seq + 1)
        executor.shutdown()
    }

    @Test
    fun `nickname change reaches the rooms of the user`() {
        val roomId = roomWith(ALICE, BOB)
        val stream = openStream(ALICE, roomId)
        snapshot(stream)

        updateProfile(BOB, "Bobby", Avatar.DOG)

        val event = patches(stream, 1).single()
        assertThat(event["code"]).isEqualTo("profile-changed")
        assertThat(path(event)).isEqualTo(listOf("members", mapOf("id" to BOB.toString())))
        assertThat(value(event)).isEqualTo(mapOf("nickname" to "Bobby", "avatar" to "DOG"))
    }

    @Test
    fun `deleted room sends the last event and closes the streams`() {
        val roomId = roomWith(ALICE, BOB)
        val stream = openStream(BOB, roomId)
        snapshot(stream)
        setPublishStatus("nothing", "NOT_PUBLISHED")

        notPublishedGameCleanupJob.run()

        assertThat(awaitEvents(stream, 2).map { it.first }).containsExactly("snapshot", "info")
        assertThat(patches(stream, 1).single()["code"]).isEqualTo("room-deleted")
        awaitUntil("streams are closed") { roomStreamRegistry.subscribers(roomId).isEmpty() }
    }

    @Test
    fun `event over the NOTIFY payload limit is not published`() {
        val roomId = roomWith(ALICE)
        val member = RoomMemberDetailsModel(
            RoomMemberModel(roomId, ALICE, RoomRoleModel.PLAYER, Instant.now()),
            UserProfileModel(ALICE, "x".repeat(8000), UserAvatar.CAT),
        )

        assertThatThrownBy {
            transactionTemplate.executeWithoutResult {
                lockRoom(roomId)
                roomEventPublisher.profileChanged(roomId, member)
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("too large")
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

    private fun snapshot(stream: MvcResult): RoomSnapshotDto {
        val (type, data) = awaitEvents(stream, 1).first()
        assertThat(type).isEqualTo("snapshot")
        return jsonMapper.readValue(data, RoomSnapshotDto::class.java)
    }

    /** Ждёт [count] событий после снапшота и возвращает их `data` как JSON-деревья. */
    @Suppress("UNCHECKED_CAST")
    private fun patches(stream: MvcResult, count: Int): List<Map<String, Any?>> =
        awaitEvents(stream, count + 1).drop(1).map { jsonMapper.readValue(it.second, Map::class.java) as Map<String, Any?> }

    @Suppress("UNCHECKED_CAST")
    private fun data(event: Map<String, Any?>) = event["data"] as Map<String, Any?>

    private fun path(event: Map<String, Any?>) = data(event)["path"]

    @Suppress("UNCHECKED_CAST")
    private fun value(event: Map<String, Any?>) = data(event)["value"] as Map<String, Any?>

    /** Кадр события → операция для общего применителя. */
    private fun operation(event: Map<String, Any?>): PatchOperation {
        val method = PatchMethod.valueOf(event["method"] as String)
        val path = if (method == PatchMethod.INFO) emptyList() else (path(event) as List<*>).map {
            when (it) {
                is String -> PathSegment.Key(it)
                else -> PathSegment.Id((it as Map<*, *>)["id"] as String)
            }
        }
        val value = if (method == PatchMethod.PUT || method == PatchMethod.PATCH) data(event)["value"] else null
        return Operation(event["code"] as String, method, path, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun tree(room: RoomResponse) = jsonMapper.convertValue(room, Map::class.java) as Map<String, Any?>

    private fun eventSeq(roomId: UUID): Long = db { RoomTable.selectAll().where { RoomTable.id eq roomId }.single()[RoomTable.eventSeq] }

    private fun lockRoom(roomId: UUID) {
        RoomTable.selectAll().where { RoomTable.id eq roomId }.forUpdate().single()
    }

    private fun lockWaiters(): Long = count(PgStatActivity) { PgStatActivity.waitEventType eq "Lock" }

    private data class Operation(
        override val code: String,
        override val method: PatchMethod,
        override val path: List<PathSegment>,
        override val value: Any?,
    ) : PatchOperation

    /** Системное представление PostgreSQL с активностью соединений — только для теста ожидания блокировки. */
    private object PgStatActivity : Table("pg_stat_activity") {
        val waitEventType = varchar("wait_event_type", 64).nullable()
    }
}
