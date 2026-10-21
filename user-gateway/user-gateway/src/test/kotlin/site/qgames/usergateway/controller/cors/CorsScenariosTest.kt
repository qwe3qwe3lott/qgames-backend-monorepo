package site.qgames.usergateway.controller.cors

import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import site.qgames.usergateway.BaseIntegrationTest
import site.qgames.userauth.gatewayapi.dto.auth.LoginRequest

/**
 * Разрешённый origin берётся из application.yml по умолчанию: только портал, http://localhost:3000.
 * Админка (3001) ходит в admin-gateway, а не сюда.
 */
class CorsScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "preflight from {0}")
    @ValueSource(strings = ["http://localhost:3000"])
    fun `preflight from an allowed origin permits credentialed requests`(origin: String) {
        mockMvc.options("/api/auth/login") {
            header(HttpHeaders.ORIGIN, origin)
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
        }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin) }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true") }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")) }
        }
    }

    @ParameterizedTest(name = "preflight from {0}")
    @ValueSource(
        strings = ["https://evil.example.com", "http://localhost:8081", "https://qgames.site.evil.com", "http://localhost:3001", "http://localhost:5173"],
    )
    fun `preflight from a foreign origin is rejected`(origin: String) {
        mockMvc.options("/api/auth/login") {
            header(HttpHeaders.ORIGIN, origin)
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        }.andExpect {
            status { isForbidden() }
            header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
        }
    }

    @ParameterizedTest(name = "login from {0}, allowed: {1}")
    @CsvSource(
        "http://localhost:3000, true",
        "https://evil.example.com, false",
    )
    fun `actual request is served only for an allowed origin`(origin: String, allowed: Boolean) {
        stubUserAuthPost("/api/auth/login", 200, authResponse())

        val result = mockMvc.post("/api/auth/login") {
            header(HttpHeaders.ORIGIN, origin)
            contentType = MediaType.APPLICATION_JSON
            content = json(LoginRequest("player@example.com", "correct-horse-1"))
        }

        if (allowed) {
            result.andExpect {
                status { isOk() }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin) }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true") }
            }
            assertThat(result.setCookie("access_token")).isNotNull()
        } else {
            result.andExpect {
                status { isForbidden() }
                header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
            }
            userAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
        }
    }

    @Test
    fun `authenticated request from an allowed origin gets CORS headers`() {
        stubUserAuthGet("/api/users/me", user())

        mockMvc.get("/api/users/me") {
            header(HttpHeaders.ORIGIN, "http://localhost:3000")
            cookie(Cookie("access_token", accessToken()))
        }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000") }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true") }
        }
    }
}
