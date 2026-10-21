package site.qgames.usergateway.controller.room

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import site.qgames.common.web.header.QgamesHeaders
import site.qgames.game.gatewayapi.api.SessionEventsApi
import site.qgames.usergateway.BaseIntegrationTest
import java.util.UUID

/**
 * Поток событий партии: gateway проверяет JWT и код игры, открывает поток в game (его эмулирует WireMock) с X-User-Id
 * и пересылает байты как есть. Ошибки до начала потока — ProblemDetail, как у остальных ручек.
 */
class SessionEventsProxyScenariosTest : BaseIntegrationTest() {

    @Test
    fun `stream of game is forwarded byte for byte with the user id`() {
        stubStream(200, STREAM, MediaType.TEXT_EVENT_STREAM_VALUE)

        val result = mockMvc.get(PATH) {
            cookie(Cookie("access_token", accessToken()))
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

        result.getAsyncResult(10_000)
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk)
        val response = result.response
        assertThat(response.getContentAsString(Charsets.UTF_8)).isEqualTo(STREAM)
        assertThat(response.contentType).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache")
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no")
        userAuth.verify(
            1,
            getRequestedFor(urlEqualTo(PATH))
                .withHeader(QgamesHeaders.USER_ID, equalTo(USER_ID.toString()))
                .withHeader(HttpHeaders.ACCEPT, equalTo(MediaType.TEXT_EVENT_STREAM_VALUE)),
        )
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource("403, NOT_ROOM_MEMBER", "404, SESSION_NOT_FOUND", "409, GAME_TYPE_MISMATCH")
    fun `game error before the stream is forwarded unchanged`(status: Int, code: String) {
        stubStream(status, json(problem(status, code)), MediaType.APPLICATION_PROBLEM_JSON_VALUE)

        mockMvc.get(PATH) {
            cookie(Cookie("access_token", accessToken()))
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isEqualTo(status) }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value(code) }
        }
    }

    @Test
    fun `unknown game code is rejected without calling game`() {
        mockMvc.get(PATH.replace("/nothing/", "/chess/")) {
            cookie(Cookie("access_token", accessToken()))
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
        userAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
    }

    @Test
    fun `stream requires authentication and does not call game`() {
        mockMvc.get(PATH) {
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
        userAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
    }

    private fun stubStream(status: Int, body: String, contentType: String) {
        userAuth.stubFor(
            get(urlEqualTo(PATH)).willReturn(
                aResponse().withStatus(status).withHeader(HttpHeaders.CONTENT_TYPE, contentType).withBody(body),
            ),
        )
    }

    companion object {
        private val ROOM_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000aa")
        private val SESSION_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000bb")
        private val PATH = SessionEventsApi.PATH
            .replace("{gameCode}", "nothing")
            .replace("{roomId}", ROOM_ID.toString())
            .replace("{sessionId}", SESSION_ID.toString())

        private val STREAM = "event:snapshot\ndata:{\"seq\":1,\"status\":\"IN_PROGRESS\"}\n\n" +
            ": ping\n\n" +
            "event:patch\ndata:{\"seq\":2,\"at\":\"2026-10-21T10:00:00Z\",\"method\":\"PATCH\",\"code\":\"player-passed\"," +
            "\"data\":{\"path\":[\"state\"],\"value\":{\"moves\":1}}}\n\n" +
            "event:patch\ndata:{\"seq\":3,\"at\":\"2026-10-21T10:00:00Z\",\"method\":\"PATCH\",\"code\":\"session-finished\"," +
            "\"data\":{\"path\":[],\"value\":{\"status\":\"FINISHED\"}}}\n\n"
    }
}
