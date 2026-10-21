package site.qgames.admingateway.controller.auth

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import site.qgames.adminauth.gatewayapi.dto.auth.LoginRequest
import site.qgames.adminauth.gatewayapi.dto.auth.RefreshTokenRequest
import site.qgames.admingateway.BaseIntegrationTest
import site.qgames.common.web.header.QgamesHeaders
import java.time.Instant
import java.util.stream.Stream

class AuthCookiesScenariosTest : BaseIntegrationTest() {

    @Test
    fun `successful login puts tokens only into http-only admin cookies`() {
        val auth = authResponse()
        stubAdminAuthPost("/api/auth/login", 200, auth)

        val result = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json(LoginRequest("root", "correct-horse-1"))
            // Даже валидная кука не должна превращаться в X-User-Id на эндпоинтах аутентификации.
            cookie(Cookie(ACCESS_COOKIE, accessToken()))
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(ADMIN_ID.toString()) }
            jsonPath("$.login") { value("root") }
            jsonPath("$.createdAt") { value("2026-10-20T10:00:00Z") }
            jsonPath("$.tokens") { doesNotExist() }
            jsonPath("$.accessToken") { doesNotExist() }
            jsonPath("$.refreshToken") { doesNotExist() }
        }

        assertThat(result.setCookie(ACCESS_COOKIE))
            .startsWith("$ACCESS_COOKIE=${auth.tokens.accessToken};")
            .contains("Path=/;", "HttpOnly", "Secure", "SameSite=Lax")
            .containsPattern("Max-Age=(299|300);")
        assertThat(result.setCookie(REFRESH_COOKIE))
            .startsWith("$REFRESH_COOKIE=${auth.tokens.refreshToken};")
            .contains("Path=/api/auth;", "HttpOnly", "Secure", "SameSite=Lax")
            .containsPattern("Max-Age=(899|900);")
        // Куки портала админка не ставит: иначе локально (оба gateway на localhost) они перетёрли бы сессию игрока.
        assertThat(result.setCookie("access_token")).isNull()
        assertThat(result.setCookie("refresh_token")).isNull()
        adminAuth.verify(
            postRequestedFor(urlEqualTo("/api/auth/login"))
                .withRequestBody(equalToJson(json(LoginRequest("root", "correct-horse-1"))))
                .withoutHeader(QgamesHeaders.USER_ID),
        )
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("downstreamErrors")
    fun `admin-auth error is forwarded unchanged and no session is started`(status: Int, code: String) {
        stubAdminAuthPost("/api/auth/login", status, problem(status, code))

        val result = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json(LoginRequest("root", "wrong-password"))
        }.andExpect {
            status { isEqualTo(status) }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value(code) }
        }

        assertThat(result.andReturn().response.getHeaders("Set-Cookie")).isEmpty()
    }

    @Test
    fun `refresh takes the token from cookie and works even with an expired access cookie`() {
        val renewed = authResponse(refreshToken = "refresh-token-2")
        stubAdminAuthPost("/api/auth/refresh", 200, renewed)

        val result = mockMvc.post("/api/auth/refresh") {
            cookie(
                Cookie(ACCESS_COOKIE, accessToken(expiresAt = Instant.now().minusSeconds(120))),
                Cookie(REFRESH_COOKIE, "refresh-token-1"),
            )
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(ADMIN_ID.toString()) }
            jsonPath("$.tokens") { doesNotExist() }
        }

        assertThat(result.setCookie(ACCESS_COOKIE)).startsWith("$ACCESS_COOKIE=${renewed.tokens.accessToken};")
        assertThat(result.setCookie(REFRESH_COOKIE))
            .startsWith("$REFRESH_COOKIE=refresh-token-2;")
            .contains("Path=/api/auth;")
            .containsPattern("Max-Age=(899|900);")
        adminAuth.verify(
            postRequestedFor(urlEqualTo("/api/auth/refresh"))
                .withRequestBody(equalToJson(json(RefreshTokenRequest("refresh-token-1"))))
                .withoutHeader(QgamesHeaders.USER_ID),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedRefreshes")
    fun `failed refresh clears both cookies`(
        case: String,
        refreshCookie: Cookie?,
        downstreamCode: String,
        expectedCode: String,
        adminAuthCalls: Int,
    ) {
        stubAdminAuthPost("/api/auth/refresh", 401, problem(401, downstreamCode))

        val result = mockMvc.post("/api/auth/refresh") {
            refreshCookie?.let { cookie(it) }
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value(expectedCode) }
        }

        assertThat(result.setCookie(ACCESS_COOKIE)).contains("Max-Age=0", "Path=/;")
        assertThat(result.setCookie(REFRESH_COOKIE)).contains("Max-Age=0", "Path=/api/auth;")
        adminAuth.verify(adminAuthCalls, postRequestedFor(urlEqualTo("/api/auth/refresh")))
    }

    @ParameterizedTest(name = "refresh cookie present: {0}")
    @MethodSource("logoutCases")
    fun `logout revokes the session in admin-auth when possible and always clears cookies`(withCookie: Boolean) {
        stubAdminAuthPost("/api/auth/logout", 200, emptyMap<String, Any>())

        val result = mockMvc.post("/api/auth/logout") {
            if (withCookie) cookie(Cookie(REFRESH_COOKIE, "refresh-token-1"))
        }.andExpect { status { isOk() } }

        assertThat(result.setCookie(ACCESS_COOKIE)).contains("Max-Age=0")
        assertThat(result.setCookie(REFRESH_COOKIE)).contains("Max-Age=0", "Path=/api/auth;")
        adminAuth.verify(
            if (withCookie) 1 else 0,
            postRequestedFor(urlEqualTo("/api/auth/logout"))
                .withRequestBody(equalToJson(json(RefreshTokenRequest("refresh-token-1")))),
        )
    }

    @Test
    fun `portal refresh cookie is not an admin session`() {
        mockMvc.post("/api/auth/refresh") {
            cookie(Cookie("refresh_token", "portal-refresh-token"))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
        }

        adminAuth.verify(0, postRequestedFor(urlEqualTo("/api/auth/refresh")))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreachableEndpoints")
    fun `unreachable admin-auth yields service unavailable`(path: String, requestBody: Any?, refreshCookie: Cookie?) {
        adminAuth.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

        mockMvc.post(path) {
            // Клиент ждёт JSON, а ошибка всё равно приходит как ProblemDetail.
            accept = MediaType.APPLICATION_JSON
            requestBody?.let {
                contentType = MediaType.APPLICATION_JSON
                content = json(it)
            }
            refreshCookie?.let { cookie(it) }
        }.andExpect {
            status { isServiceUnavailable() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("SERVICE_UNAVAILABLE") }
        }
        adminAuth.verify(1, postRequestedFor(urlEqualTo(path)))
    }

    companion object {
        @JvmStatic
        fun downstreamErrors(): Stream<Arguments> = Stream.of(
            arguments(400, "VALIDATION_ERROR"),
            arguments(401, "INVALID_CREDENTIALS"),
        )

        @JvmStatic
        fun rejectedRefreshes(): Stream<Arguments> = Stream.of(
            arguments("no refresh cookie", null, "REFRESH_TOKEN_REUSED", "INVALID_REFRESH_TOKEN", 0),
            arguments(
                "unknown or expired refresh token",
                Cookie(REFRESH_COOKIE, "expired-token"), "INVALID_REFRESH_TOKEN", "INVALID_REFRESH_TOKEN", 1,
            ),
            arguments(
                "reused refresh token",
                Cookie(REFRESH_COOKIE, "stolen-token"), "REFRESH_TOKEN_REUSED", "REFRESH_TOKEN_REUSED", 1,
            ),
        )

        @JvmStatic
        fun logoutCases(): Stream<Arguments> = Stream.of(arguments(true), arguments(false))

        @JvmStatic
        fun unreachableEndpoints(): Stream<Arguments> = Stream.of(
            arguments("/api/auth/login", LoginRequest("root", "correct-horse-1"), null),
            arguments("/api/auth/refresh", null, Cookie(REFRESH_COOKIE, "refresh-token-1")),
            arguments("/api/auth/logout", null, Cookie(REFRESH_COOKIE, "refresh-token-1")),
        )
    }
}
