package site.qgames.admingateway.controller.cors

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
import site.qgames.admingateway.BaseIntegrationTest
import site.qgames.admingateway.publicapi.dto.auth.AdminLoginRequest

/**
 * Разрешённый origin берётся из application.yml по умолчанию: только админка, http://localhost:3001.
 * Портал (3000) ходит в user-gateway, а не сюда.
 */
class CorsScenariosTest : BaseIntegrationTest() {

    @Test
    fun `preflight from the admin panel permits credentialed requests`() {
        mockMvc.options("/api/auth/login") {
            header(HttpHeaders.ORIGIN, ADMIN_ORIGIN)
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
        }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ADMIN_ORIGIN) }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true") }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")) }
        }
    }

    @ParameterizedTest(name = "preflight from {0}")
    @ValueSource(strings = ["http://localhost:3000", "https://evil.example.com", "http://localhost:8083", "http://localhost:3001.evil.com"])
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
        "http://localhost:3001, true",
        "http://localhost:3000, false",
    )
    fun `actual request is served only for the admin panel origin`(origin: String, allowed: Boolean) {
        stubAdminAuthPost("/api/auth/login", 200, authResponse())

        val result = mockMvc.post("/api/auth/login") {
            header(HttpHeaders.ORIGIN, origin)
            contentType = MediaType.APPLICATION_JSON
            content = json(AdminLoginRequest("root", "correct-horse-1"))
        }

        if (allowed) {
            result.andExpect {
                status { isOk() }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin) }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true") }
            }
            assertThat(result.setCookie(ACCESS_COOKIE)).isNotNull()
        } else {
            result.andExpect {
                status { isForbidden() }
                header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
            }
            adminAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
        }
    }

    @Test
    fun `authenticated request from the admin panel gets CORS headers`() {
        stubAdminAuthGet("/api/admins/me", 200, admin())

        mockMvc.get("/api/admins/me") {
            header(HttpHeaders.ORIGIN, ADMIN_ORIGIN)
            cookie(Cookie(ACCESS_COOKIE, accessToken()))
        }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ADMIN_ORIGIN) }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true") }
        }
    }

    companion object {
        private const val ADMIN_ORIGIN = "http://localhost:3001"
    }
}
