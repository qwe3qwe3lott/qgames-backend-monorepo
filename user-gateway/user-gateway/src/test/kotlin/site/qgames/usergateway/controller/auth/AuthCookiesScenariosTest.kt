package site.qgames.usergateway.controller.auth

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
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
import org.springframework.test.web.servlet.put
import site.qgames.common.web.header.QgamesHeaders
import site.qgames.userauth.gatewayapi.dto.auth.RefreshTokenRequest
import site.qgames.usergateway.BaseIntegrationTest
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.auth.LoginRequest
import site.qgames.userauth.gatewayapi.dto.auth.VerifyEmailRequest
import site.qgames.userauth.gatewayapi.dto.user.ChangePasswordRequest
import java.time.Instant
import java.util.stream.Stream

class AuthCookiesScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("sessionEndpoints")
    fun `successful authentication puts tokens only into http-only cookies`(case: String, path: String, request: Any) {
        val auth = authResponse()
        stubUserAuthPost(path, 200, auth)

        val result = mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = json(request)
            // Даже валидная кука не должна превращаться в X-User-Id на публичных эндпоинтах.
            cookie(Cookie("access_token", accessToken()))
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(USER_ID.toString()) }
            jsonPath("$.tokens") { doesNotExist() }
            jsonPath("$.accessToken") { doesNotExist() }
        }

        assertThat(result.setCookie("access_token"))
            .startsWith("access_token=${auth.tokens.accessToken};")
            .contains("Path=/;", "HttpOnly", "Secure", "SameSite=Lax")
            .containsPattern("Max-Age=(899|900);")
        assertThat(result.setCookie("refresh_token"))
            .startsWith("refresh_token=${auth.tokens.refreshToken};")
            .contains("Path=/api/auth;", "HttpOnly", "Secure", "SameSite=Lax")
            .containsPattern("Max-Age=259(1999|2000);")
        userAuth.verify(
            postRequestedFor(urlEqualTo(path))
                .withRequestBody(equalToJson(json(request)))
                .withoutHeader(QgamesHeaders.USER_ID),
        )
    }

    @Test
    fun `password change forwards user id and renews session cookies`() {
        val auth = authResponse(refreshToken = "refresh-after-password-change")
        stubUserAuthPut(PASSWORD_PATH, 200, auth)
        val request = ChangePasswordRequest("correct-horse-1", "brand-new-password")

        val result = mockMvc.put(PASSWORD_PATH) {
            contentType = MediaType.APPLICATION_JSON
            content = json(request)
            cookie(Cookie("access_token", accessToken()))
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(USER_ID.toString()) }
            jsonPath("$.tokens") { doesNotExist() }
        }

        assertThat(result.setCookie("access_token")).startsWith("access_token=${auth.tokens.accessToken};").contains("Path=/;")
        assertThat(result.setCookie("refresh_token"))
            .startsWith("refresh_token=refresh-after-password-change;")
            .contains("Path=/api/auth;", "HttpOnly")
        userAuth.verify(
            putRequestedFor(urlEqualTo(PASSWORD_PATH))
                .withHeader(QgamesHeaders.USER_ID, equalTo(USER_ID.toString()))
                .withRequestBody(equalToJson(json(request))),
        )
    }

    @Test
    fun `rejected password change keeps the session cookies`() {
        stubUserAuthPut(PASSWORD_PATH, 400, problem(400, "INVALID_CURRENT_PASSWORD"))

        val result = mockMvc.put(PASSWORD_PATH) {
            contentType = MediaType.APPLICATION_JSON
            content = json(ChangePasswordRequest("wrong-password", "brand-new-password"))
            cookie(Cookie("access_token", accessToken()))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_CURRENT_PASSWORD") }
        }

        assertThat(result.andReturn().response.getHeaders("Set-Cookie")).isEmpty()
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("downstreamErrors")
    fun `user-auth error is forwarded unchanged and no session is started`(status: Int, code: String) {
        stubUserAuthPost("/api/auth/login", status, problem(status, code))

        val result = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json(LoginRequest("player@example.com", "password"))
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
        stubUserAuthPost("/api/auth/refresh", 200, renewed)

        val result = mockMvc.post("/api/auth/refresh") {
            cookie(
                Cookie("access_token", accessToken(expiresAt = Instant.now().minusSeconds(60))),
                Cookie("refresh_token", "refresh-token-1"),
            )
        }.andExpect { status { isOk() } }

        assertThat(result.setCookie("refresh_token")).startsWith("refresh_token=refresh-token-2;")
        userAuth.verify(
            postRequestedFor(urlEqualTo("/api/auth/refresh"))
                .withRequestBody(equalToJson(json(RefreshTokenRequest("refresh-token-1")))),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedRefreshes")
    fun `failed refresh clears both cookies`(case: String, refreshCookie: Cookie?, expectedCode: String, userAuthCalls: Int) {
        stubUserAuthPost("/api/auth/refresh", 401, problem(401, "REFRESH_TOKEN_REUSED"))

        val result = mockMvc.post("/api/auth/refresh") {
            refreshCookie?.let { cookie(it) }
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value(expectedCode) }
        }

        assertThat(result.setCookie("access_token")).contains("Max-Age=0")
        assertThat(result.setCookie("refresh_token")).contains("Max-Age=0", "Path=/api/auth;")
        userAuth.verify(userAuthCalls, postRequestedFor(urlEqualTo("/api/auth/refresh")))
    }

    @ParameterizedTest(name = "refresh cookie present: {0}")
    @MethodSource("logoutCases")
    fun `logout revokes the session in user-auth when possible and always clears cookies`(withCookie: Boolean) {
        stubUserAuthPost("/api/auth/logout", 200, emptyMap<String, Any>())

        val result = mockMvc.post("/api/auth/logout") {
            if (withCookie) cookie(Cookie("refresh_token", "refresh-token-1"))
        }.andExpect { status { isOk() } }

        assertThat(result.setCookie("access_token")).contains("Max-Age=0")
        assertThat(result.setCookie("refresh_token")).contains("Max-Age=0")
        userAuth.verify(
            if (withCookie) 1 else 0,
            postRequestedFor(urlEqualTo("/api/auth/logout"))
                .withRequestBody(equalToJson(json(RefreshTokenRequest("refresh-token-1")))),
        )
    }

    @Test
    fun `registration is proxied without starting a session`() {
        stubUserAuthPost("/api/auth/register", 200, mapOf("userId" to USER_ID, "email" to "player@example.com"))

        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"player@example.com","password":"correct-horse-1","nickname":"Player","avatar":"CAT"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.userId") { value(USER_ID.toString()) }
        }

        assertThat(result.andReturn().response.getHeaders("Set-Cookie")).isEmpty()
        userAuth.verify(
            postRequestedFor(urlEqualTo("/api/auth/register")).withRequestBody(
                equalToJson("""{"email":"player@example.com","password":"correct-horse-1","nickname":"Player","avatar":"CAT"}"""),
            ),
        )
    }

    @Test
    fun `unreachable user-auth yields service unavailable`() {
        userAuth.stubFor(post(urlEqualTo("/api/auth/login")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json(LoginRequest("player@example.com", "password"))
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("SERVICE_UNAVAILABLE") }
        }
        userAuth.verify(1, postRequestedFor(urlEqualTo("/api/auth/login")))
    }

    companion object {
        private const val PASSWORD_PATH = "/api/users/me/login-methods/password"

        @JvmStatic
        fun sessionEndpoints(): Stream<Arguments> = Stream.of(
            arguments("password login", "/api/auth/login", LoginRequest("player@example.com", "correct-horse-1")),
            arguments("email verification", "/api/auth/verify-email", VerifyEmailRequest("player@example.com", "123456")),
            arguments("google login", "/api/auth/google", GoogleIdTokenRequest("google-id-token")),
        )

        @JvmStatic
        fun downstreamErrors(): Stream<Arguments> = Stream.of(
            arguments(400, "VALIDATION_ERROR"),
            arguments(401, "INVALID_CREDENTIALS"),
            arguments(403, "EMAIL_NOT_VERIFIED"),
            arguments(429, "TOO_MANY_VERIFICATION_ATTEMPTS"),
        )

        @JvmStatic
        fun rejectedRefreshes(): Stream<Arguments> = Stream.of(
            arguments("no refresh cookie", null, "INVALID_REFRESH_TOKEN", 0),
            arguments("refresh token rejected by user-auth", Cookie("refresh_token", "stolen-token"), "REFRESH_TOKEN_REUSED", 1),
        )

        @JvmStatic
        fun logoutCases(): Stream<Arguments> = Stream.of(arguments(true), arguments(false))
    }
}
