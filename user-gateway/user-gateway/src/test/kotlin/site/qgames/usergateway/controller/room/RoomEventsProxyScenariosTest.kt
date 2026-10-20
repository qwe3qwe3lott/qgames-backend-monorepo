package site.qgames.usergateway.controller.room

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.http.Fault
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
import site.qgames.game.gatewayapi.api.RoomEventsApi
import site.qgames.usergateway.BaseIntegrationTest
import java.util.UUID

/**
 * Поток событий комнаты: gateway проверяет JWT, открывает поток в game (его эмулирует WireMock) с X-User-Id и
 * пересылает байты как есть. Ошибки до начала потока — ProblemDetail, как у остальных ручек.
 */
class RoomEventsProxyScenariosTest : BaseIntegrationTest() {

    @Test
    fun `stream of game is forwarded byte for byte with the user id`() {
        stubStream(200, STREAM, MediaType.TEXT_EVENT_STREAM_VALUE)

        val result = mockMvc.get(PATH) {
            cookie(Cookie("access_token", accessToken()))
            header(HttpHeaders.ORIGIN, "http://localhost:3000")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

        result.getAsyncResult(10_000)
        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk)
        val response = result.response
        assertThat(response.getContentAsString(Charsets.UTF_8)).isEqualTo(STREAM)
        assertThat(response.contentType).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache")
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no")
        // EventSource с withCredentials: ответ разрешён origin портала вместе с куками.
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("http://localhost:3000")
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true")
        userAuth.verify(
            1,
            getRequestedFor(urlEqualTo(PATH))
                .withHeader(QgamesHeaders.USER_ID, equalTo(USER_ID.toString()))
                .withHeader(HttpHeaders.ACCEPT, equalTo(MediaType.TEXT_EVENT_STREAM_VALUE)),
        )
    }

    /** Сообщения уходят клиенту по мере прихода из game, а не после конца потока. */
    @Test
    fun `events are forwarded as they arrive`() {
        userAuth.stubFor(
            get(urlEqualTo(PATH)).willReturn(
                aResponse().withStatus(200).withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .withBody(STREAM).withChunkedDribbleDelay(4, 8000),
            ),
        )

        val result = mockMvc.get(PATH) {
            cookie(Cookie("access_token", accessToken()))
            accept = MediaType.TEXT_EVENT_STREAM
        }.andReturn()

        val started = System.nanoTime()
        while (result.response.contentAsByteArray.isEmpty() && System.nanoTime() - started < 7_000_000_000L) {
            Thread.sleep(50)
        }
        assertThat(result.response.contentAsByteArray).isNotEmpty()
        assertThat(result.response.getContentAsString(Charsets.UTF_8)).isNotEqualTo(STREAM)
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource("404, ROOM_NOT_FOUND", "404, GAME_NOT_FOUND", "401, UNAUTHENTICATED")
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
    fun `unavailable game is service unavailable`() {
        userAuth.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

        mockMvc.get(PATH) {
            cookie(Cookie("access_token", accessToken()))
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isServiceUnavailable() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("SERVICE_UNAVAILABLE") }
        }
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
        private val PATH = RoomEventsApi.PATH.replace("{roomId}", ROOM_ID.toString())

        private val STREAM = "event:snapshot\ndata:{\"id\":\"$ROOM_ID\",\"members\":[]}\n\n" +
            ": ping\n\n" +
            "event:users-changed\ndata:{\"ownerId\":null,\"members\":[]}\n\n" +
            "event:room-deleted\ndata:{}\n\n"
    }
}
