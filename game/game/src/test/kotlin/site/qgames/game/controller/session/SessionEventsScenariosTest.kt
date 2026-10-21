package site.qgames.game.controller.session

import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
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
import site.qgames.common.web.header.QgamesHeaders
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.api.SessionEventsApi
import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.gatewayapi.dto.session.MoveOptionDto
import site.qgames.game.gatewayapi.dto.session.NothingSessionStateDto
import site.qgames.game.gatewayapi.dto.session.SessionPlayerDto
import site.qgames.game.gatewayapi.dto.session.SessionTimerStatus
import site.qgames.game.gatewayapi.dto.session.SessionTurnsDto
import site.qgames.game.job.game.ForceDisabledGameCleanupJob
import site.qgames.game.job.room.RoomConnectionHeartbeatJob
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.support.TestData.EVE
import site.qgames.games.api.session.ViewerKey
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.stream.Stream
import kotlin.concurrent.thread

/**
 * Поток партии (SSE): первым — снапшот зрителя на seq, дальше события журнала с непрерывным seq, замаскированные под
 * зрителя. Рассылка — через шину (Postgres NOTIFY), только после коммита.
 */
class SessionEventsScenariosTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var roomConnectionHeartbeatJob: RoomConnectionHeartbeatJob

    @Autowired
    private lateinit var forceDisabledGameCleanupJob: ForceDisabledGameCleanupJob

    /** Контроллер game объявляет маршрут из константы контракта, по которой gateway строит клиента. */
    @Test
    fun `stream route matches the gateway contract`() {
        assertThat(SessionEventsApi.PATH).isEqualTo("/api/games/{gameCode}/rooms/{roomId}/sessions/{sessionId}/events")
        val roomId = roomWith(ALICE, BOB, started = true)

        val stream = openSessionStream(ALICE, roomId)

        assertThat(awaitEvents(stream, 1).single().first).isEqualTo("snapshot")
        assertThat(stream.response.contentType).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)
        assertThat(sessionStreamRegistry.subscribers(sessionId(roomId))).hasSize(1)
    }

    @Test
    fun `snapshots differ by the mask of the viewer`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(rounds = 2, mode = TimeControlMode.PER_MOVE, moveSeconds = 30))
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
            assertThat(snapshot.path("meta").path("game").path("rules").path("gameSettings").path("rounds").asInt()).isEqualTo(2)
            val state = jsonMapper.treeToValue(snapshot.path("state"), NothingSessionStateDto::class.java)
            assertThat(state.turns).isEqualTo(SessionTurnsDto(ALICE, round = 1, rounds = 2))
            assertThat(state.timer!!.status).isEqualTo(SessionTimerStatus.RUNNING)
            assertThat(state.moves).isZero()
        }
    }

    @Test
    fun `move reaches every viewer with its own mask and continuous seq`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(mode = TimeControlMode.PER_MOVE, moveSeconds = 30))
        join(CAROL, roomId)
        val streams = mapOf(ALICE to openSessionStream(ALICE, roomId), BOB to openSessionStream(BOB, roomId), CAROL to openSessionStream(CAROL, roomId))
        streams.values.forEach { awaitEvents(it, 1) }

        pass(ALICE, roomId)

        val received = streams.mapValues { (_, stream) -> awaitEvents(stream, 6) }
        received.values.forEach { events ->
            assertThat(events.map { it.first })
                .containsExactly("snapshot", "moved", "turns-changed", "player-changed", "player-changed", "timer-changed")
            assertThat(events.drop(1).map { frame(it).path("seq").asLong() }).containsExactly(2L, 3L, 4L, 5L, 6L)
            val moved = frame(events[1]).path("data")
            assertThat(moved.path("playerId").asString()).isEqualTo(ALICE.toString())
            assertThat(moved.path("type").asString()).isEqualTo("PASS")
            assertThat(moved.path("timeout").asBoolean()).isFalse()
            assertThat(frame(events[2]).path("data").path("currentPlayerId").asString()).isEqualTo(BOB.toString())
            assertThat(frame(events[5]).path("data").path("status").asString()).isEqualTo("RUNNING")
        }
        // Варианты хода Боба целиком видит только Боб, остальным приходит маска.
        fun bobOptions(viewer: UUID) = frame(received.getValue(viewer)[4]).path("data").path("moveOptions").size()
        assertThat(bobOptions(BOB)).isEqualTo(1)
        assertThat(bobOptions(ALICE)).isZero()
        assertThat(bobOptions(CAROL)).isZero()
    }

    @Test
    fun `pause and resume are timer-changed in the session stream, the room stream is not touched`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(mode = TimeControlMode.PER_MOVE, moveSeconds = 30))
        val roomStream = openStream(ALICE, roomId)
        awaitEvents(roomStream, 1)
        val stream = openSessionStream(BOB, roomId)
        awaitEvents(stream, 1)

        pause(ALICE, roomId)
        resume(ALICE, roomId)

        val events = awaitEvents(stream, 3)
        assertThat(events.map { it.first }).containsExactly("snapshot", "timer-changed", "timer-changed")
        assertThat(frame(events[1]).path("data").path("status").asString()).isEqualTo("PAUSED")
        assertThat(frame(events[1]).path("data").path("pausedAt").isNull).isFalse()
        assertThat(frame(events[2]).path("data").path("status").asString()).isEqualTo("RUNNING")
        assertThat(frame(events[2]).path("data").path("pausedAt").isNull).isTrue()
        assertThat(events(roomStream).map { it.first }).containsExactly("snapshot")
    }

    @Test
    fun `end of the game is the last event and closes the stream`() {
        val roomId = roomWith(ALICE, started = true, settings = nothingSettings(rounds = 1))
        val stream = openSessionStream(ALICE, roomId)
        awaitEvents(stream, 1)

        pass(ALICE, roomId)

        assertThat(awaitEvents(stream, 5).map { it.first })
            .containsExactly("snapshot", "moved", "turns-changed", "player-changed", "session-finished")
        awaitUntil("stream is closed") { sessionStreamRegistry.subscribers(sessionId(roomId)).isEmpty() }
    }

    @Test
    fun `timer is stopped right before the end of a timed game`() {
        val roomId = roomWith(ALICE, started = true, settings = nothingSettings(rounds = 1, mode = TimeControlMode.PER_MOVE, moveSeconds = 30))
        val stream = openSessionStream(ALICE, roomId)
        awaitEvents(stream, 1)

        pass(ALICE, roomId)

        val events = awaitEvents(stream, 6)
        assertThat(events.map { it.first })
            .containsExactly("snapshot", "moved", "turns-changed", "player-changed", "timer-changed", "session-finished")
        val stopped = frame(events[4]).path("data")
        assertThat(stopped.path("status").asString()).isEqualTo(SessionTimerStatus.STOPPED.name)
        assertThat(stopped.path("deadline").isNull).isTrue()
        assertThat(stopped.path("pausedAt").isNull).isTrue()
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
        // Отмена обнулила варианты ходов (player-changed), как и конец партии.
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
        setPublishStatus("nothing", "FORCE_DISABLED")

        forceDisabledGameCleanupJob.run()

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
            header(QgamesHeaders.USER_ID, user.toString())
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
