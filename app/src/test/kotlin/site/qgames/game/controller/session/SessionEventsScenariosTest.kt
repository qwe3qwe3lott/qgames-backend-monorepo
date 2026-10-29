package site.qgames.game.controller.session

import io.mockk.every
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.room.SessionStatus
import site.qgames.game.api.dto.session.MoveOptionDto
import site.qgames.game.api.dto.session.NothingSessionStateDto
import site.qgames.game.api.dto.session.SessionPatchStreamEvent
import site.qgames.game.api.dto.session.SessionPlayerDto
import site.qgames.game.api.dto.session.SessionStreamEvent
import site.qgames.game.api.dto.session.SessionTimerStatus
import site.qgames.game.api.dto.session.SessionTurnsDto
import site.qgames.game.job.game.NotPublishedGameCleanupJob
import site.qgames.game.job.room.RoomConnectionHeartbeatJob
import site.qgames.game.job.session.MoveTimeoutJob
import site.qgames.game.mapper.session.SessionEventJsonMapper
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.support.TestData.EVE
import site.qgames.game.table.session.RoomSessionTable
import site.qgames.common.docpatch.DocumentPatch
import site.qgames.games.core.ViewerKey
import tools.jackson.databind.JsonNode

/**
 * Поток партии (SSE): первым — снапшот зрителя на seq, дальше события журнала с непрерывным seq, замаскированные под
 * зрителя. Рассылка — через Postgres NOTIFY, только после коммита и в порядке коммитов.
 */
class SessionEventsScenariosTest : GameIntegrationTest() {

    @Autowired
    private lateinit var roomConnectionHeartbeatJob: RoomConnectionHeartbeatJob

    @Autowired
    private lateinit var notPublishedGameCleanupJob: NotPublishedGameCleanupJob

    @Autowired
    private lateinit var moveTimeoutJob: MoveTimeoutJob

    @Autowired
    private lateinit var sessionEventJsonMapper: SessionEventJsonMapper

    /** Поток не кешируется и не буферизуется прокси. */
    @Test
    fun `stream is not cached or buffered by proxies`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        val stream = openSessionStream(ALICE, roomId)

        assertThat(awaitEvents(stream, 1).single().first).isEqualTo("snapshot")
        assertThat(stream.response.contentType).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)
        assertThat(sessionStreamRegistry.subscribers(sessionId(roomId))).hasSize(1)
        assertThat(stream.response.getHeader("Cache-Control")).isEqualTo("no-cache")
        assertThat(stream.response.getHeader("X-Accel-Buffering")).isEqualTo("no")
    }

    @Test
    fun `snapshots differ by the mask of the viewer`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(rounds = 2, clockSeconds = 30))
        join(CAROL, roomId)

        val alice = snapshot(openSessionStream(ALICE, roomId))
        val bob = snapshot(openSessionStream(BOB, roomId))
        val carol = snapshot(openSessionStream(CAROL, roomId))

        val pass = listOf(MoveOptionDto("PASS", null))
        assertThat(players(alice)).containsExactly(SessionPlayerDto(ALICE, pass), SessionPlayerDto(BOB, emptyList()))
        assertThat(players(bob)).containsExactly(SessionPlayerDto(ALICE, emptyList()), SessionPlayerDto(BOB, emptyList()))
        assertThat(players(carol)).containsExactly(SessionPlayerDto(ALICE, emptyList()), SessionPlayerDto(BOB, emptyList()))
        listOf(alice, bob, carol).forEach { snapshot ->
            assertThat(snapshot.path("seq").asLong()).isEqualTo(1)
            assertThat(snapshot.path("status").asString()).isEqualTo("IN_PROGRESS")
            assertThat(snapshot.has("serverTime")).isTrue()
            assertThat(snapshot.path("meta").path("game").path("code").asString()).isEqualTo(GameCode.NOTHING.code)
            assertThat(snapshot.path("meta").path("game").path("settings").path("rounds").asInt()).isEqualTo(2)
            val state = jsonMapper.treeToValue(snapshot.path("state"), NothingSessionStateDto::class.java)
            assertThat(state.turns).isEqualTo(SessionTurnsDto(ALICE, round = 1, rounds = 2))
            assertThat(state.timer!!.status).isEqualTo(SessionTimerStatus.RUNNING)
            assertThat(state.moves).isZero()
        }
    }

    @Test
    fun `move reaches every viewer with its own mask and continuous seq`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(clockSeconds = 30))
        join(CAROL, roomId)
        val streams = mapOf(ALICE to openSessionStream(ALICE, roomId), BOB to openSessionStream(BOB, roomId), CAROL to openSessionStream(CAROL, roomId))
        streams.values.forEach { awaitEvents(it, 1) }

        pass(ALICE, roomId)

        val received = streams.mapValues { (_, stream) -> awaitEvents(stream, 6) }
        received.values.forEach { events ->
            assertThat(events.map { it.first }).containsExactly("snapshot", "patch", "put", "put", "put", "put")
            val frames = events.drop(1).map(::frame)
            assertThat(frames.map { it.path("seq").asLong() }).containsExactly(2L, 3L, 4L, 5L, 6L)
            assertThat(frames.map { it.path("code").asString() })
                .containsExactly("player-passed", "turn-changed", "move-options-changed", "move-options-changed", "clock-switched")
            assertThat(frames.map { it.path("method").asString() }).containsExactly("PATCH", "PUT", "PUT", "PUT", "PUT")
            assertThat(frames[0].path("data")).isEqualTo(json("""{"path": ["state"], "value": {"moves": 1}}"""))
            assertThat(frames[1].path("data")).isEqualTo(
                json("""{"path": ["state", "turns"], "value": {"currentPlayerId": "$BOB", "round": 1, "rounds": 3}}"""),
            )
            assertThat(frames[3].path("data").path("path")).isEqualTo(json("""["state", "players", {"id": "$BOB"}, "moveOptions"]"""))
            assertThat(frames[4].path("data").path("path")).isEqualTo(json("""["state", "timer"]"""))
            assertThat(frames[4].path("data").path("value").path("status").asString()).isEqualTo("RUNNING")
            frames.forEach { assertThat(it.path("at").isString).isTrue() }
        }
        // Варианты хода Боба целиком видит только Боб, остальным приходит маска.
        fun bobOptions(viewer: UUID) = frame(received.getValue(viewer)[4]).path("data").path("value")
        assertThat(bobOptions(BOB)).isEqualTo(json("""[{"type": "PASS", "data": null}]"""))
        assertThat(bobOptions(ALICE)).isEqualTo(json("[]"))
        assertThat(bobOptions(CAROL)).isEqualTo(json("[]"))
    }

    @Test
    fun `pause and resume are timer patches in the session stream, the room stream is not touched`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(clockSeconds = 30))
        val roomStream = openStream(ALICE, roomId)
        awaitEvents(roomStream, 1)
        val stream = openSessionStream(BOB, roomId)
        awaitEvents(stream, 1)

        pause(ALICE, roomId)
        resume(ALICE, roomId)

        val events = awaitEvents(stream, 3)
        assertThat(events.map { it.first }).containsExactly("snapshot", "patch", "patch")
        val paused = frame(events[1])
        assertThat(paused.path("code").asString()).isEqualTo("game-paused")
        assertThat(paused.path("data").path("path")).isEqualTo(json("""["state", "timer"]"""))
        assertThat(paused.path("data").path("value").path("status").asString()).isEqualTo("PAUSED")
        assertThat(paused.path("data").path("value").path("pausedAt").isNull).isFalse()
        val resumed = frame(events[2])
        assertThat(resumed.path("code").asString()).isEqualTo("game-resumed")
        assertThat(resumed.path("data").path("value").path("status").asString()).isEqualTo("RUNNING")
        assertThat(resumed.path("data").path("value").path("pausedAt").isNull).isTrue()
        assertThat(resumed.path("data").path("value").has("deadline")).isTrue()
        assertThat(events(roomStream).map { it.first }).containsExactly("snapshot")
    }

    @Test
    fun `end of the game is the last event and closes the stream`() {
        val roomId = roomWith(ALICE, started = true, settings = nothingSettings(rounds = 1))
        val stream = openSessionStream(ALICE, roomId)
        awaitEvents(stream, 1)

        pass(ALICE, roomId)

        val events = awaitEvents(stream, 5)
        assertThat(events.map { it.first }).containsExactly("snapshot", "patch", "put", "put", "patch")
        assertThat(events.drop(1).map { frame(it).path("code").asString() })
            .containsExactly("player-passed", "turn-changed", "move-options-changed", "session-finished")
        assertThat(frame(events.last()).path("data")).isEqualTo(json("""{"path": [], "value": {"status": "FINISHED"}}"""))
        awaitUntil("stream is closed") { sessionStreamRegistry.subscribers(sessionId(roomId)).isEmpty() }
    }

    @Test
    fun `timer is stopped right before the end of a timed game`() {
        val roomId = roomWith(ALICE, started = true, settings = nothingSettings(rounds = 1, clockSeconds = 30))
        val stream = openSessionStream(ALICE, roomId)
        awaitEvents(stream, 1)

        pass(ALICE, roomId)

        val events = awaitEvents(stream, 6)
        assertThat(events.drop(1).map { frame(it).path("code").asString() })
            .containsExactly("player-passed", "turn-changed", "move-options-changed", "timer-stopped", "session-finished")
        // Остановленные часы хранят итоговый остаток ходившего: он зависит от времени хода, поэтому — по полям.
        val stopped = frame(events[4]).path("data")
        assertThat(stopped.path("path")).isEqualTo(json("""["state", "timer"]"""))
        assertThat(stopped.path("value").path("status").asString()).isEqualTo(SessionTimerStatus.STOPPED.name)
        assertThat(stopped.path("value").path("deadline").isNull).isTrue()
        assertThat(stopped.path("value").path("clocks").size()).isEqualTo(1)
        assertThat(stopped.path("value").path("clocks").path(0).path("playerId").asString()).isEqualTo(ALICE.toString())
    }

    @Test
    fun `cancel stops the timer, ends the stream with session-cancelled and closes it`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(clockSeconds = 30))
        val stream = openSessionStream(BOB, roomId)
        awaitEvents(stream, 1)

        cancel(ALICE, roomId)

        val events = awaitEvents(stream, 4)
        assertThat(events.drop(1).map { frame(it).path("code").asString() })
            .containsExactly("move-options-changed", "timer-stopped", "session-cancelled")
        assertThat(frame(events.last()).path("data")).isEqualTo(json("""{"path": [], "value": {"status": "CANCELLED"}}"""))
        awaitUntil("stream is closed") { sessionStreamRegistry.subscribers(sessionId(roomId)).isEmpty() }
    }

    /**
     * Клиент применяет события к снапшоту по method, не зная кодов: у каждого зрителя это даёт тот же документ, что
     * полный снапшот завершённой партии. Каждое событие разбирается схемой контракта (SessionStreamEvent).
     */
    @Test
    fun `events applied to the snapshot give the final snapshot for every viewer`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(rounds = 2, clockSeconds = 30))
        join(CAROL, roomId)
        val sessionId = sessionId(roomId)
        val streams = listOf(ALICE, BOB, CAROL).map { openSessionStream(it, roomId) }
        streams.forEach { awaitEvents(it, 1) }

        pass(ALICE, roomId)
        pause(ALICE, roomId)
        resume(ALICE, roomId)
        pass(BOB, roomId)
        db { RoomSessionTable.update({ RoomSessionTable.id eq sessionId }) { it[moveDeadline] = Instant.now().minusSeconds(1) } }
        assertThat(moveTimeoutJob.run()).isEqualTo(1)
        pass(BOB, roomId)

        streams.forEach { stream -> awaitUntil("session-finished") { events(stream).any { frame(it).path("code").asString() == "session-finished" } } }
        val final = document(snapshot(openSessionStream(ALICE, roomId, sessionId)))
        assertThat(final["status"]).isEqualTo("FINISHED")
        streams.forEach { stream ->
            val events = events(stream)
            // Состояние в снапшоте — union без дискриминатора (игра — meta.game.code), его проверяют другие тесты.
            events.drop(1).forEach { (type, data) ->
                assertThat(jsonMapper.readValue("""{"type": "$type", "data": $data}""", SessionStreamEvent::class.java))
                    .isInstanceOf(SessionPatchStreamEvent::class.java)
            }
            val applied = events.drop(1).fold(document(jsonMapper.readTree(events.first().second))) { document, event ->
                val frame = frame(event)
                val patch = sessionEventJsonMapper.read(frame.path("method").asString(), frame.path("code").asString(), frame.path("data").toString())
                DocumentPatch.apply(document, patch)
            }
            assertThat(applied).isEqualTo(final)
        }
    }

    @Test
    fun `subscription to a finished game sends the full snapshot and closes`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        val sessionId = sessionId(roomId)
        cancel(ALICE, roomId)

        val stream = openSessionStream(BOB, roomId, sessionId)

        val snapshot = snapshot(stream)
        assertThat(snapshot.path("status").asString()).isEqualTo(SessionStatus.CANCELLED.name)
        // После конца партии всё публично: снапшот собирается полной проекцией, а не проекцией игрока.
        verify { sessionStreamService.snapshot(sessionId, ViewerKey.Full) }
        assertThat(snapshot.path("seq").asLong()).isEqualTo(journal(roomId).size.toLong())
        // Отмена обнулила варианты ходов (move-options-changed), как и конец партии.
        assertThat(players(snapshot)).containsExactly(SessionPlayerDto(ALICE, emptyList()), SessionPlayerDto(BOB, emptyList()))
        assertThat(jsonMapper.treeToValue(snapshot.path("state"), NothingSessionStateDto::class.java).timer).isNull()
        awaitUntil("stream is closed") { sessionStreamRegistry.subscribers(sessionId).isEmpty() }
        assertThat(events(stream)).hasSize(1)
    }

    @Test
    fun `events committed before the snapshot are not repeated and later ones are not lost`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        val sessionId = sessionId(roomId)
        every { sessionStreamService.snapshot(sessionId, any()) } answers {
            // Подписчик уже зарегистрирован: ход до снапшота войдёт в снапшот, его уведомление отбрасывается…
            thread { pass(ALICE, roomId) }.join()
            Thread.sleep(300)
            val snapshot = callOriginal()
            // …а ход после снапшота, пришедший раньше отправки снапшота, копится и уходит следом.
            thread { pass(BOB, roomId) }.join()
            Thread.sleep(300)
            snapshot
        }

        val stream = openSessionStream(CAROL.also { join(it, roomId) }, roomId)

        val events = awaitEvents(stream, 5)
        assertThat(frame(events.first()).path("seq").asLong()).isEqualTo(5)
        assertThat(events.drop(1).map { frame(it).path("seq").asLong() }).containsExactly(6L, 7L, 8L, 9L)
        Thread.sleep(300)
        assertThat(events(stream)).hasSize(5)
    }

    @Test
    fun `deleted room closes the session streams`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        val sessionId = sessionId(roomId)
        val stream = openSessionStream(BOB, roomId)
        awaitEvents(stream, 1)
        setPublishStatus("nothing", "NOT_PUBLISHED")

        notPublishedGameCleanupJob.run()

        awaitUntil("stream is closed") { sessionStreamRegistry.subscribers(sessionId).isEmpty() }
        assertThat(events(stream).map { it.first }).containsExactly("snapshot")
    }

    @Test
    fun `heartbeat pings the session streams`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        val stream = openSessionStream(ALICE, roomId)
        awaitEvents(stream, 1)

        roomConnectionHeartbeatJob.run()

        awaitUntil("ping in the stream") { stream.response.getContentAsString(Charsets.UTF_8).contains(": ping\n") }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    fun `errors before the stream starts are problem details`(
        case: String,
        user: UUID,
        path: (UUID, UUID) -> String,
        expectedStatus: HttpStatus,
        expectedCode: String,
    ) {
        val roomId = roomWith(ALICE, BOB, started = true)
        val sessionId = sessionId(roomId)

        mockMvc.get(path(roomId, sessionId)) {
            asUser(user)
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isEqualTo(expectedStatus.value()) }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value(expectedCode) }
        }
        assertThat(sessionStreamRegistry.all()).isEmpty()
    }

    private fun snapshot(stream: MvcResult): JsonNode {
        val (type, data) = awaitEvents(stream, 1).first()
        assertThat(type).isEqualTo("snapshot")
        return jsonMapper.readTree(data)
    }

    private fun players(snapshot: JsonNode): List<SessionPlayerDto> =
        jsonMapper.treeToValue(snapshot.path("state"), NothingSessionStateDto::class.java).players

    private fun frame(event: Pair<String, String>): JsonNode = jsonMapper.readTree(event.second)

    private fun json(text: String): JsonNode = jsonMapper.readTree(text)

    /** Документ партии {status, meta, state} из снапшота — то, к чему клиент применяет события. */
    @Suppress("UNCHECKED_CAST")
    private fun document(snapshot: JsonNode): Map<String, Any?> =
        (jsonMapper.convertValue(snapshot, Map::class.java) as Map<String, Any?>) - setOf("seq", "serverTime")

    companion object {
        private const val PATH = "/api/games/%s/rooms/%s/sessions/%s/events"

        @JvmStatic
        fun rejections(): Stream<Arguments> = Stream.of(
            arguments("not in the room", EVE, { r: UUID, s: UUID -> PATH.format("nothing", r, s) }, HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER"),
            arguments("unknown room", ALICE, { _: UUID, s: UUID -> PATH.format("nothing", UUID.randomUUID(), s) }, HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"),
            arguments("unknown session", ALICE, { r: UUID, _: UUID -> PATH.format("nothing", r, UUID.randomUUID()) }, HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND"),
            arguments("unknown game", ALICE, { r: UUID, s: UUID -> PATH.format("chess", r, s) }, HttpStatus.NOT_FOUND, "GAME_NOT_FOUND"),
            arguments("another game", ALICE, { r: UUID, s: UUID -> PATH.format("carcassonne", r, s) }, HttpStatus.CONFLICT, "GAME_TYPE_MISMATCH"),
        )
    }
}
