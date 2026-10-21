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
import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.room.Avatar
import site.qgames.game.gatewayapi.dto.room.NothingSettingsDto
import site.qgames.game.gatewayapi.dto.room.RoomMemberResponse
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.game.gatewayapi.dto.room.RoomStatus
import site.qgames.game.gatewayapi.dto.room.SessionResponse
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.game.gatewayapi.dto.room.TimeControlDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.usergateway.BaseIntegrationTest
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

/**
 * Публичные ручки комнат и игр проксируются в сервис game (его эмулирует WireMock) с X-User-Id из JWT.
 */
class RoomProxyScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("operations")
    fun `room operation is proxied to game with the user id`(case: String, method: HttpMethod, path: String, body: String?, returnsRoom: Boolean) {
        stubGame(method, path, if (returnsRoom) 200 else 204, if (returnsRoom) json(gameRoom()) else null)

        mockMvc.request(method, path) {
            cookie(Cookie("access_token", accessToken()))
            body?.let {
                contentType = MediaType.APPLICATION_JSON
                content = it
            }
        }.andExpect {
            status { isEqualTo(if (returnsRoom) 200 else 204) }
            if (returnsRoom) {
                jsonPath("$.id") { value(ROOM_ID.toString()) }
                jsonPath("$.members[0].nickname") { value("Player") }
                jsonPath("$.members[0].avatar") { value("DOG") }
                jsonPath("$.session.id") { value(SESSION_ID.toString()) }
                jsonPath("$.session.status") { value("IN_PROGRESS") }
                jsonPath("$.members[0].leaving") { value(true) }
                jsonPath("$.settings.timeControl.mode") { value("PER_MOVE") }
                jsonPath("$.settings.timeControl.moveSeconds") { value(30) }
                jsonPath("$.settings.gameSettings.gameCode") { value("nothing") }
                jsonPath("$.settings.gameSettings.rounds") { value(5) }
                jsonPath("$.serverTime") { value("2026-10-18T10:00:10Z") }
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
        "409, SESSION_PAUSED",
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
    fun `settings errors from game are forwarded with their fields`() {
        val path = "/api/games/nothing/rooms"
        val errors = mapOf("settings.gameSettings.rounds" to "must be between 1 and 50")
        stubGame(HttpMethod.POST, path, 400, json(problem(400, "VALIDATION_ERROR") + ("errors" to errors)))

        mockMvc.request(HttpMethod.POST, path) {
            cookie(Cookie("access_token", accessToken()))
            contentType = MediaType.APPLICATION_JSON
            content = settingsBody("settings", rounds = 99)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors['settings.gameSettings.rounds']") { value("must be between 1 and 50") }
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "POST, /api/games/chess/rooms, true",
        "PUT, /api/games/chess/rooms/01990000-0000-7000-8000-0000000000aa/settings, true",
        "POST, /api/games/chess/rooms/01990000-0000-7000-8000-0000000000aa/moves, false",
        "GET, /api/games/chess, false",
    )
    fun `unknown game code is rejected without calling game`(method: HttpMethod, path: String, settings: Boolean) {
        mockMvc.request(method, path) {
            cookie(Cookie("access_token", accessToken()))
            if (method != HttpMethod.GET) {
                contentType = MediaType.APPLICATION_JSON
                content = when {
                    !settings -> """{"type":"PASS"}"""
                    path.endsWith("/settings") -> settingsBody(null)
                    else -> settingsBody("settings")
                }
            }
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
        userAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSettingsBodies")
    fun `structurally invalid settings are rejected without calling game`(
        case: String,
        method: HttpMethod,
        path: String,
        body: String,
        field: String?,
    ) {
        mockMvc.request(method, path) {
            cookie(Cookie("access_token", accessToken()))
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            if (field != null) jsonPath("$.errors['$field']") { exists() }
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

        /** Тело с настройками комнаты; [wrapper] — имя поля-обёртки (`settings` у создания комнаты). */
        fun settingsBody(wrapper: String?, rounds: Int = 5): String {
            val settings = """{"timeControl":{"mode":"PER_MOVE","moveSeconds":30},"gameSettings":{"gameCode":"nothing","rounds":$rounds}}"""
            return if (wrapper == null) settings else """{"$wrapper":$settings}"""
        }

        fun gameRoom() = RoomResponse(
            id = ROOM_ID,
            gameCode = GameCode.NOTHING,
            status = RoomStatus.IN_GAME,
            ownerId = USER_ID,
            minPlayers = 1,
            maxPlayers = 4,
            members = listOf(
                RoomMemberResponse(
                    USER_ID, "Player", Avatar.DOG, RoomRole.PLAYER, owner = true, leaving = true,
                    joinedAt = Instant.parse("2026-10-18T10:00:00Z"),
                ),
            ),
            session = SessionResponse(id = SESSION_ID, status = SessionStatus.IN_PROGRESS),
            createdAt = Instant.parse("2026-10-18T10:00:00Z"),
            settings = RoomSettingsDto(TimeControlDto(TimeControlMode.PER_MOVE, 30), NothingSettingsDto(GameCode.NOTHING, rounds = 5)),
            serverTime = Instant.parse("2026-10-18T10:00:10Z"),
        )

        @JvmStatic
        fun operations(): Stream<Arguments> = Stream.of(
            arguments("create", HttpMethod.POST, "/api/games/nothing/rooms", settingsBody("settings"), true),
            arguments("update settings", HttpMethod.PUT, "/api/games/nothing/rooms/$ROOM_ID/settings", settingsBody(null), true),
            arguments("get", HttpMethod.GET, "/api/rooms/$ROOM_ID", null, true),
            arguments("change role", HttpMethod.PUT, "/api/rooms/$ROOM_ID/role", """{"role":"SPECTATOR"}""", true),
            arguments("start", HttpMethod.POST, "/api/rooms/$ROOM_ID/session", null, true),
            arguments("cancel", HttpMethod.DELETE, "/api/rooms/$ROOM_ID/session", null, false),
            arguments("pause", HttpMethod.POST, "/api/rooms/$ROOM_ID/session/pause", null, false),
            arguments("resume", HttpMethod.POST, "/api/rooms/$ROOM_ID/session/resume", null, false),
            arguments(
                "move", HttpMethod.POST, "/api/games/nothing/rooms/$ROOM_ID/moves",
                """{"type":"PLACE","data":{"x":1}}""", false,
            ),
        )

        @JvmStatic
        fun invalidSettingsBodies(): Stream<Arguments> {
            val create = "/api/games/nothing/rooms"
            val update = "/api/games/nothing/rooms/$ROOM_ID/settings"
            return Stream.of(
                arguments("create without settings", HttpMethod.POST, create, "{}", null),
                arguments("create body with the game code only", HttpMethod.POST, create, """{"gameCode":"nothing"}""", null),
                arguments(
                    "game settings without discriminator", HttpMethod.POST, create,
                    """{"settings":{"timeControl":{"mode":"NONE"},"gameSettings":{"rounds":3}}}""", "settings.gameSettings.gameCode",
                ),
                arguments(
                    "unknown discriminator", HttpMethod.POST, create,
                    """{"settings":{"timeControl":{"mode":"NONE"},"gameSettings":{"gameCode":"chess","rounds":3}}}""", "settings.gameSettings.gameCode",
                ),
                arguments(
                    "unknown time control mode", HttpMethod.PUT, update,
                    """{"timeControl":{"mode":"CHESS"},"gameSettings":{"gameCode":"nothing","rounds":3}}""", null,
                ),
                arguments("settings without time control", HttpMethod.PUT, update, """{"gameSettings":{"gameCode":"nothing","rounds":3}}""", null),
                arguments(
                    "unknown discriminator in update", HttpMethod.PUT, update,
                    """{"timeControl":{"mode":"NONE"},"gameSettings":{"gameCode":"chess","rounds":3}}""", "gameSettings.gameCode",
                ),
            )
        }
    }
}
