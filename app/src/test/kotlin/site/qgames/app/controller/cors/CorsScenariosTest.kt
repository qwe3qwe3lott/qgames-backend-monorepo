package site.qgames.app.controller.cors

import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import site.qgames.BaseIntegrationTest
import site.qgames.userauth.api.dto.auth.LoginRequest
import site.qgames.userauth.support.TestData

/**
 * У каждого фронта свои пути: портал (http://localhost:3000) — `/api/…` кроме `/api/admin/…`, админка
 * (http://localhost:3001) — только `/api/admin/…`. Чужой origin получает 403 без CORS-заголовков.
 */
class CorsScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "preflight {1} from {0}, allowed: {2}")
    @CsvSource(
        "http://localhost:3000, /api/auth/login, true",
        "http://localhost:3001, /api/admin/auth/login, true",
        "http://localhost:3001, /api/auth/login, false",
        "http://localhost:3000, /api/admin/auth/login, false",
        "https://evil.example.com, /api/auth/login, false",
        "https://evil.example.com, /api/admin/auth/login, false",
        "https://qgames.site.evil.com, /api/auth/login, false",
        "http://localhost:5173, /api/auth/login, false",
        "http://localhost:8080, /api/auth/login, false",
    )
    fun `preflight is allowed only for the origin of the path`(origin: String, path: String, allowed: Boolean) {
        mockMvc.options(path) {
            header(HttpHeaders.ORIGIN, origin)
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
        }.andExpect {
            if (allowed) {
                status { isOk() }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin) }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true") }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")) }
            } else {
                status { isForbidden() }
                header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
            }
        }
    }

    @ParameterizedTest(name = "login from {0}, allowed: {1}")
    @CsvSource(
        "http://localhost:3000, true",
        "http://localhost:3001, false",
        "https://evil.example.com, false",
    )
    fun `actual portal request is served only for the portal origin`(origin: String, allowed: Boolean) {
        val result = mockMvc.post("/api/auth/login") {
            header(HttpHeaders.ORIGIN, origin)
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(LoginRequest(TestData.PasswordOnly.EMAIL, TestData.PASSWORD))
        }

        result.andExpect {
            if (allowed) {
                status { isOk() }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin) }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true") }
                header { exists(HttpHeaders.SET_COOKIE) }
            } else {
                status { isForbidden() }
                header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
                header { doesNotExist(HttpHeaders.SET_COOKIE) }
            }
        }
    }

    @Test
    fun `authenticated request from an allowed origin gets CORS headers`() {
        mockMvc.get("/api/users/me") {
            header(HttpHeaders.ORIGIN, "http://localhost:3000")
            cookie(Cookie("access_token", userAccessToken(TestData.PasswordOnly.ID.toString())))
        }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000") }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true") }
        }
    }
}
