package site.qgames.app.controller.auth

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import site.qgames.BaseIntegrationTest
import site.qgames.userauth.api.dto.auth.LoginRequest
import site.qgames.userauth.api.dto.user.ChangePasswordRequest
import java.time.Duration
import java.util.stream.Stream
import site.qgames.adminauth.api.dto.auth.LoginRequest as AdminLoginRequest
import site.qgames.adminauth.support.TestData as AdminTestData
import site.qgames.userauth.support.TestData as UserTestData

/**
 * Куки сессии портала и админки: токены — только в HttpOnly-куках со своими именами и путями, в теле их нет.
 * Портал: access_token (`Path=/`, 15 мин), refresh_token (`Path=/api/auth`, 30 дней). Админка: admin_access_token
 * (`Path=/api/admin`, 5 мин), admin_refresh_token (`Path=/api/admin/auth`, 15 мин).
 */
class AuthCookiesScenariosTest : BaseIntegrationTest() {

    /** Сторона аутентификации: портал или админка. */
    enum class Side(
        val authPath: String,
        val accessCookie: String,
        val refreshCookie: String,
        val accessPath: String,
        val accessMaxAge: Duration,
        val refreshMaxAge: Duration,
        val login: Any,
    ) {
        PORTAL(
            "/api/auth", "access_token", "refresh_token", "/", Duration.ofMinutes(15), Duration.ofDays(30),
            LoginRequest(UserTestData.PasswordOnly.LOGIN, UserTestData.PASSWORD),
        ),
        ADMIN(
            "/api/admin/auth", "admin_access_token", "admin_refresh_token", "/api/admin", Duration.ofMinutes(5), Duration.ofMinutes(15),
            AdminLoginRequest(AdminTestData.Root.LOGIN, AdminTestData.PASSWORD),
        ),
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sides")
    fun `login puts tokens only into http-only cookies`(side: Side) {
        val result = login(side).andExpect {
            status { isOk() }
            jsonPath("$.id") { exists() }
            jsonPath("$.tokens") { doesNotExist() }
            jsonPath("$.accessToken") { doesNotExist() }
        }

        assertThat(setCookie(result, side.accessCookie))
            .contains("Path=${side.accessPath};", "HttpOnly", "Secure", "SameSite=Lax")
            .containsPattern("Max-Age=(${side.accessMaxAge.seconds - 1}|${side.accessMaxAge.seconds});")
        assertThat(setCookie(result, side.refreshCookie))
            .contains("Path=${side.authPath};", "HttpOnly", "Secure", "SameSite=Lax")
            .containsPattern("Max-Age=(${side.refreshMaxAge.seconds - 1}|${side.refreshMaxAge.seconds});")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sides")
    fun `refresh takes the token from cookie and works even with an expired access cookie`(side: Side) {
        val refreshToken = cookieValue(setCookie(login(side), side.refreshCookie))
        val expiredAccess = when (side) {
            Side.PORTAL -> userAccessToken(UserTestData.PasswordOnly.ID.toString(), Duration.ofMinutes(-5))
            Side.ADMIN -> adminAccessToken(AdminTestData.Root.ID.toString(), Duration.ofMinutes(-5))
        }

        val result = mockMvc.post("${side.authPath}/refresh") {
            cookie(Cookie(side.accessCookie, expiredAccess), Cookie(side.refreshCookie, refreshToken))
        }.andExpect { status { isOk() } }

        assertThat(cookieValue(setCookie(result, side.refreshCookie))).isNotEqualTo(refreshToken).isNotBlank()
    }

    @ParameterizedTest(name = "{0}, refresh cookie: {1}")
    @MethodSource("rejectedRefreshes")
    fun `failed refresh clears both cookies`(side: Side, refreshToken: String?, expectedCode: String) {
        val result = mockMvc.post("${side.authPath}/refresh") {
            refreshToken?.let { cookie(Cookie(side.refreshCookie, it)) }
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value(expectedCode) }
        }

        assertThat(setCookie(result, side.accessCookie)).contains("Max-Age=0", "Path=${side.accessPath};")
        assertThat(setCookie(result, side.refreshCookie)).contains("Max-Age=0", "Path=${side.authPath};")
    }

    @ParameterizedTest(name = "{0}, refresh cookie present: {1}")
    @MethodSource("logoutCases")
    fun `logout revokes the session when possible and always clears cookies`(side: Side, withCookie: Boolean) {
        val refreshToken = cookieValue(setCookie(login(side), side.refreshCookie))

        val result = mockMvc.post("${side.authPath}/logout") {
            if (withCookie) cookie(Cookie(side.refreshCookie, refreshToken))
        }.andExpect { status { isOk() } }

        assertThat(setCookie(result, side.accessCookie)).contains("Max-Age=0")
        assertThat(setCookie(result, side.refreshCookie)).contains("Max-Age=0")
        mockMvc.post("${side.authPath}/refresh") { cookie(Cookie(side.refreshCookie, refreshToken)) }
            .andExpect { status { if (withCookie) isUnauthorized() else isOk() } }
    }

    @Test
    fun `password change renews portal session cookies`() {
        val result = mockMvc.put("/api/users/me/login-methods/password") {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(ChangePasswordRequest(UserTestData.PASSWORD, "brand-new-password"))
            cookie(Cookie("access_token", userAccessToken(UserTestData.PasswordOnly.ID.toString())))
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(UserTestData.PasswordOnly.ID.toString()) }
            jsonPath("$.tokens") { doesNotExist() }
        }

        assertThat(setCookie(result, "access_token")).contains("Path=/;", "HttpOnly")
        assertThat(setCookie(result, "refresh_token")).contains("Path=/api/auth;", "HttpOnly")
    }

    @Test
    fun `rejected password change keeps the session cookies`() {
        val result = mockMvc.put("/api/users/me/login-methods/password") {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(ChangePasswordRequest("wrong-password", "brand-new-password"))
            cookie(Cookie("access_token", userAccessToken(UserTestData.PasswordOnly.ID.toString())))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_CURRENT_PASSWORD") }
        }

        assertThat(result.andReturn().response.getHeaders("Set-Cookie")).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sides")
    fun `rejected login starts no session`(side: Side) {
        val body = when (side) {
            Side.PORTAL -> LoginRequest(UserTestData.PasswordOnly.LOGIN, "wrong-password")
            Side.ADMIN -> AdminLoginRequest(AdminTestData.Root.LOGIN, "wrong-password")
        }
        val result = mockMvc.post("${side.authPath}/login") {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
        }.andExpect {
            status { isUnauthorized() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("INVALID_CREDENTIALS") }
        }

        assertThat(result.andReturn().response.getHeaders("Set-Cookie")).isEmpty()
    }

    private fun login(side: Side): ResultActionsDsl =
        mockMvc.post("${side.authPath}/login") {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(side.login)
        }.andExpect { status { isOk() } }

    private fun setCookie(result: ResultActionsDsl, name: String): String =
        result.andReturn().response.getHeaders("Set-Cookie").single { it.startsWith("$name=") }

    private fun cookieValue(setCookie: String): String = setCookie.substringAfter('=').substringBefore(';')

    companion object {
        @JvmStatic
        fun sides(): Stream<Arguments> = Side.entries.map { arguments(it) }.stream()

        @JvmStatic
        fun rejectedRefreshes(): Stream<Arguments> = Side.entries.flatMap { side ->
            listOf(
                arguments(side, null, "INVALID_REFRESH_TOKEN"),
                arguments(side, "never-issued-token", "INVALID_REFRESH_TOKEN"),
            )
        }.stream()

        @JvmStatic
        fun logoutCases(): Stream<Arguments> = Side.entries.flatMap { listOf(arguments(it, true), arguments(it, false)) }.stream()
    }
}
