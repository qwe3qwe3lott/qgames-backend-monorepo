package site.qgames.usergateway.controller.room

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.request
import com.github.tomakehurst.wiremock.client.WireMock.requestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request
import site.qgames.common.web.header.QgamesHeaders
import site.qgames.game.gatewayapi.dto.room.Avatar
import site.qgames.game.gatewayapi.dto.room.GameType
import site.qgames.game.gatewayapi.dto.room.RoomMemberResponse
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.gatewayapi.dto.room.RoomStatus
import site.qgames.game.gatewayapi.dto.room.SessionResponse
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.usergateway.BaseIntegrationTest
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

/**
 * Публичные ручки комнат проксируются в сервис game (его эмулирует WireMock) с X-User-Id из JWT.
 */
class RoomProxyScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("operations")
    fun `room operation is proxied to game with the user id`(case: String, method: HttpMethod, path: String, body: String?, returnsRoom: Boolean) {
        stubGame(method, path, 200, if (returnsRoom) json(gameRoom()) else null)

        mockMvc.request(method, path) {
            cookie(Cookie("access_token", accessToken()))
            body?.let {
                contentType = MediaType.APPLICATION_JSON
                content = it
            }
        }.andExpect {
            status { isOk() }
            if (returnsRoom) {
                jsonPath("$.id") { value(ROOM_ID.toString()) }
                jsonPath("$.members[0].nickname") { value("Player") }
                jsonPath("$.members[0].avatar") { value("DOG") }
                jsonPath("$.session.state.currentPlayer") { value(USER_ID.toString()) }
            }
        }

        val expected = requestedFor(method.name(), urlEqualTo(path)).withHeader(QgamesHeaders.USER_ID, equalTo(USER_ID.toString()))
        body?.let { expected.withRequestBody(equalToJson(it)) }
        userAuth.verify(1, expected)
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "404, ROOM_NOT_FOUND",
        "403, NOT_ROOM_OWNER",
        "409, SESSION_CONFLICT",
        "422, MOVE_REJECTED",
    )
    fun `game error is forwarded unchanged`(status: Int, code: String) {
        val path = "/api/games/nothing/rooms/$ROOM_ID/moves"
        stubGame(HttpMethod.POST, path, status, json(problem(status, code) + ("reason" to "NOT_YOUR_TURN")))

        mockMvc.request(HttpMethod.POST, path) {
            cookie(Cookie("access_token", accessToken()))
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"PASS"}"""
        }.andExpect {
            status { isEqualTo(status) }
            jsonPath("$.code") { value(code) }
            jsonPath("$.reason") { value("NOT_YOUR_TURN") }
        }
    }

    @Test
    fun `move with an unknown game code is rejected without calling game`() {
        mockMvc.request(HttpMethod.POST, "/api/games/chess/rooms/$ROOM_ID/moves") {
            cookie(Cookie("access_token", accessToken()))
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"PASS"}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
        userAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
    }

    @Test
    fun `rooms require authentication`() {
        mockMvc.request(HttpMethod.GET, "/api/rooms/$ROOM_ID").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
        userAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
    }

    private fun stubGame(method: HttpMethod, path: String, status: Int, body: String?) {
        val response = aResponse().withStatus(status)
        if (body != null) {
            response.withHeader(
                HttpHeaders.CONTENT_TYPE,
                if (status >= 400) MediaType.APPLICATION_PROBLEM_JSON_VALUE else MediaType.APPLICATION_JSON_VALUE,
            ).withBody(body)
        }
        userAuth.stubFor(request(method.name(), urlEqualTo(path)).willReturn(response))
    }

    companion object {
        private val ROOM_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000aa")
        private val SESSION_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000bb")

        fun gameRoom() = RoomResponse(
            id = ROOM_ID,
            gameType = GameType.NOTHING,
            status = RoomStatus.IN_GAME,
            ownerId = USER_ID,
            minPlayers = 1,
            maxPlayers = 4,
            members = listOf(
                RoomMemberResponse(USER_ID, "Player", Avatar.DOG, RoomRole.PLAYER, owner = true, joinedAt = Instant.parse("2026-10-18T10:00:00Z")),
            ),
            session = SessionResponse(
                id = SESSION_ID,
                status = SessionStatus.IN_PROGRESS,
                players = listOf(USER_ID),
                version = 3,
                state = mapOf("currentPlayer" to USER_ID.toString(), "round" to 2),
            ),
            createdAt = Instant.parse("2026-10-18T10:00:00Z"),
        )

        @JvmStatic
        fun operations(): Stream<Arguments> = Stream.of(
            arguments("create", HttpMethod.POST, "/api/rooms", """{"gameType":"NOTHING"}""", true),
            arguments("get", HttpMethod.GET, "/api/rooms/$ROOM_ID", null, true),
            arguments("join", HttpMethod.POST, "/api/rooms/$ROOM_ID/join", null, true),
            arguments("leave", HttpMethod.POST, "/api/rooms/$ROOM_ID/leave", null, false),
            arguments("change role", HttpMethod.PUT, "/api/rooms/$ROOM_ID/role", """{"role":"SPECTATOR"}""", true),
            arguments("delete", HttpMethod.DELETE, "/api/rooms/$ROOM_ID", null, false),
            arguments("start", HttpMethod.POST, "/api/rooms/$ROOM_ID/session", null, true),
            arguments("cancel", HttpMethod.DELETE, "/api/rooms/$ROOM_ID/session", null, true),
            arguments(
                "move", HttpMethod.POST, "/api/games/nothing/rooms/$ROOM_ID/moves",
                """{"type":"PLACE","data":{"x":1},"expectedVersion":3}""", true,
            ),
        )
    }
}
