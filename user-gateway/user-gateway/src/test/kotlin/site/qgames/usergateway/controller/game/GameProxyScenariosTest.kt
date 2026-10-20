package site.qgames.usergateway.controller.game

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.request
import com.github.tomakehurst.wiremock.client.WireMock.requestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request
import site.qgames.common.web.header.QgamesHeaders
import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.game.GameDetailsResponse
import site.qgames.game.gatewayapi.dto.game.GameListResponse
import site.qgames.game.gatewayapi.dto.game.GamePublishStatus
import site.qgames.game.gatewayapi.dto.game.GameRulesDto
import site.qgames.game.gatewayapi.dto.game.GameSummaryDto
import site.qgames.game.gatewayapi.dto.game.TimeControlLimitsDto
import site.qgames.game.gatewayapi.dto.room.IntRangeDto
import site.qgames.game.gatewayapi.dto.room.NothingSettingsDto
import site.qgames.game.gatewayapi.dto.room.NothingSettingsLimitsDto
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.game.gatewayapi.dto.room.TimeControlDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.usergateway.BaseIntegrationTest

/**
 * Каталог игр проксируется в сервис game (его эмулирует WireMock) и доступен без аутентификации. Комнаты игр
 * по-прежнему требуют входа.
 */
class GameProxyScenariosTest : BaseIntegrationTest() {

    @Test
    fun `catalog is available to a guest`() {
        stubGame(HttpMethod.GET, "/api/games", 200, json(catalog()))

        mockMvc.request(HttpMethod.GET, "/api/games").andExpect {
            status { isOk() }
            jsonPath("$.games.length()") { value(2) }
            jsonPath("$.games[0].code") { value("nothing") }
            jsonPath("$.games[0].title") { value("Игра в ничто") }
            jsonPath("$.games[0].description") { value("Тестовая игра") }
            jsonPath("$.games[0].publishStatus") { value("DISABLED") }
            jsonPath("$.games[1].code") { value("carcassonne") }
            jsonPath("$.games[1].publishStatus") { value("PUBLISHED") }
        }
        userAuth.verify(1, requestedFor("GET", urlEqualTo("/api/games")).withoutHeader(QgamesHeaders.USER_ID))
    }

    @Test
    fun `game details are available to a guest`() {
        val path = "/api/games/nothing"
        stubGame(HttpMethod.GET, path, 200, json(nothingDetails()))

        mockMvc.request(HttpMethod.GET, path).andExpect {
            status { isOk() }
            jsonPath("$.code") { value("nothing") }
            jsonPath("$.title") { value("Игра в ничто") }
            jsonPath("$.publishStatus") { value("HIDDEN") }
            jsonPath("$.rules.minPlayers") { value(1) }
            jsonPath("$.rules.maxPlayers") { value(4) }
            jsonPath("$.rules.timeControl.modes[0]") { value("NONE") }
            jsonPath("$.rules.timeControl.modes[1]") { value("PER_MOVE") }
            jsonPath("$.rules.timeControl.moveSeconds.min") { value(5) }
            jsonPath("$.rules.timeControl.moveSeconds.max") { value(600) }
            jsonPath("$.rules.gameSettingsLimits.gameCode") { value("nothing") }
            jsonPath("$.rules.gameSettingsLimits.rounds.min") { value(1) }
            jsonPath("$.rules.gameSettingsLimits.rounds.max") { value(50) }
            jsonPath("$.rules.defaultSettings.timeControl.mode") { value("NONE") }
            jsonPath("$.rules.defaultSettings.gameSettings.gameCode") { value("nothing") }
            jsonPath("$.rules.defaultSettings.gameSettings.rounds") { value(3) }
        }
        userAuth.verify(1, requestedFor("GET", urlEqualTo(path)).withoutHeader(QgamesHeaders.USER_ID))
    }

    @Test
    fun `game details of a logged in user are proxied with the user id`() {
        val path = "/api/games/nothing"
        val details = nothingDetails().copy(publishStatus = GamePublishStatus.PUBLISHED)
        stubGame(HttpMethod.GET, path, 200, json(details))

        mockMvc.request(HttpMethod.GET, path) {
            cookie(Cookie("access_token", accessToken()))
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value("nothing") }
            jsonPath("$.publishStatus") { value("PUBLISHED") }
            jsonPath("$.rules.maxPlayers") { value(4) }
        }
        userAuth.verify(1, requestedFor("GET", urlEqualTo(path)).withHeader(QgamesHeaders.USER_ID, equalTo(USER_ID.toString())))
    }

    @Test
    fun `unknown game code is rejected without calling game`() {
        mockMvc.request(HttpMethod.GET, "/api/games/chess").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
        userAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource(
        "GET, /api/games/nothing, 404, GAME_NOT_FOUND",
        "POST, /api/games/nothing/rooms, 409, GAME_UNAVAILABLE",
        "POST, /api/rooms/01990000-0000-7000-8000-0000000000aa/session, 409, GAME_UNAVAILABLE",
        "GET, /api/rooms/01990000-0000-7000-8000-0000000000aa, 404, GAME_NOT_FOUND",
    )
    fun `game availability errors are forwarded unchanged`(method: HttpMethod, path: String, status: Int, code: String) {
        stubGame(method, path, status, json(problem(status, code)))

        mockMvc.request(method, path) {
            cookie(Cookie("access_token", accessToken()))
            if (path.endsWith("/rooms")) {
                contentType = MediaType.APPLICATION_JSON
                content = """{"settings":{"timeControl":{"mode":"NONE"},"gameSettings":{"gameCode":"nothing","rounds":3}}}"""
            }
        }.andExpect {
            status { isEqualTo(status) }
            jsonPath("$.code") { value(code) }
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "POST, /api/games/nothing/rooms",
        "PUT, /api/games/nothing/rooms/01990000-0000-7000-8000-0000000000aa/settings",
        "POST, /api/games/nothing/rooms/01990000-0000-7000-8000-0000000000aa/moves",
    )
    fun `game rooms still require authentication`(method: HttpMethod, path: String) {
        mockMvc.request(method, path) {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
        userAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
    }

    private fun stubGame(method: HttpMethod, path: String, status: Int, body: String) {
        userAuth.stubFor(
            request(method.name(), urlEqualTo(path)).willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader(
                        HttpHeaders.CONTENT_TYPE,
                        if (status >= 400) MediaType.APPLICATION_PROBLEM_JSON_VALUE else MediaType.APPLICATION_JSON_VALUE,
                    )
                    .withBody(body),
            ),
        )
    }

    private companion object {
        fun catalog() = GameListResponse(
            listOf(
                GameSummaryDto(GameCode.NOTHING, "Игра в ничто", "Тестовая игра", GamePublishStatus.DISABLED),
                GameSummaryDto(GameCode.CARCASSONNE, "Каркассон", "Города и дороги", GamePublishStatus.PUBLISHED),
            ),
        )

        fun nothingDetails() = GameDetailsResponse(
            code = GameCode.NOTHING,
            title = "Игра в ничто",
            description = "Тестовая игра",
            publishStatus = GamePublishStatus.HIDDEN,
            rules = GameRulesDto(
                minPlayers = 1,
                maxPlayers = 4,
                timeControl = TimeControlLimitsDto(listOf(TimeControlMode.NONE, TimeControlMode.PER_MOVE), IntRangeDto(5, 600)),
                gameSettingsLimits = NothingSettingsLimitsDto(GameCode.NOTHING, IntRangeDto(1, 50)),
                defaultSettings = RoomSettingsDto(TimeControlDto(TimeControlMode.NONE, null), NothingSettingsDto(GameCode.NOTHING, rounds = 3)),
            ),
        )
    }
}
