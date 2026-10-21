package site.qgames.usergateway.controller.auth

import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import jakarta.servlet.http.Cookie
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.MediaType
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.request
import site.qgames.usergateway.BaseIntegrationTest
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.auth.LoginRequest
import site.qgames.userauth.gatewayapi.dto.auth.RegisterRequest
import site.qgames.userauth.gatewayapi.dto.auth.ResendCodeRequest
import site.qgames.userauth.gatewayapi.dto.auth.VerifyEmailRequest
import site.qgames.userauth.gatewayapi.dto.user.Avatar
import site.qgames.userauth.gatewayapi.dto.user.ChangePasswordRequest
import site.qgames.userauth.gatewayapi.dto.user.SetPasswordRequest
import site.qgames.userauth.gatewayapi.dto.user.UpdateProfileRequest
import java.util.stream.Stream

/**
 * Публичный контракт валидируется на gateway: заведомо невалидный запрос не доходит до сервисов.
 */
class RequestValidationScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    fun `invalid public request is rejected by gateway without calling services`(
        case: String,
        path: String,
        body: Any,
        invalidField: String,
    ) {
        val method = when {
            path.endsWith("/profile") -> HttpMethod.PATCH
            body is ChangePasswordRequest -> HttpMethod.PUT
            else -> HttpMethod.POST
        }
        mockMvc.request(method, path) {
            contentType = MediaType.APPLICATION_JSON
            content = json(body)
            cookie(Cookie("access_token", accessToken()))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors.$invalidField") { exists() }
        }

        // JWKS gateway может запросить для проверки токена — это не вызов сервиса.
        userAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
    }

    @ParameterizedTest(name = "{0} without {2}")
    @CsvSource(
        "register, /api/auth/register, password",
        "register, /api/auth/register, email",
        "verify email, /api/auth/verify-email, code",
        "set password, /api/users/me/login-methods/password, password",
    )
    fun `request without a required field is rejected by gateway`(case: String, path: String, missingField: String) {
        val body = mutableMapOf<String, Any?>(
            "email" to "player@example.com",
            "password" to "correct-horse-1",
            "nickname" to "Player",
            "avatar" to "CAT",
            "code" to "123456",
        )
        body.remove(missingField)
        mockMvc.request(HttpMethod.POST, path) {
            contentType = MediaType.APPLICATION_JSON
            content = json(body)
            cookie(Cookie("access_token", accessToken()))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }
        userAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
    }

    companion object {
        private fun register(
            email: String = "player@example.com",
            password: String = "correct-horse-1",
            nickname: String = "Player",
        ) = RegisterRequest(email, password, nickname, Avatar.CAT)

        @JvmStatic
        fun invalidRequests(): Stream<Arguments> = Stream.of(
            arguments("register with malformed email", "/api/auth/register", register(email = "not-an-email"), "email"),
            arguments("register with short password", "/api/auth/register", register(password = "short"), "password"),
            arguments("register with too short nickname", "/api/auth/register", register(nickname = "ab"), "nickname"),
            arguments("register with padded nickname", "/api/auth/register", register(nickname = " Bob "), "nickname"),
            arguments("register with nickname \" a\"", "/api/auth/register", register(nickname = " a"), "nickname"),
            arguments("register with nickname \"a \"", "/api/auth/register", register(nickname = "a "), "nickname"),
            arguments("register with control character in nickname", "/api/auth/register", register(nickname = "a\u0007b"), "nickname"),
            arguments("profile with too long nickname", "/api/users/me/profile", UpdateProfileRequest(nickname = "n".repeat(33)), "nickname"),
            arguments("verification code is not 6 digits", "/api/auth/verify-email", VerifyEmailRequest("player@example.com", "12ab"), "code"),
            arguments("resend for blank email", "/api/auth/resend-code", ResendCodeRequest(""), "email"),
            arguments("login with blank password", "/api/auth/login", LoginRequest("player@example.com", ""), "password"),
            arguments("google login with blank token", "/api/auth/google", GoogleIdTokenRequest(""), "idToken"),
            arguments("new password too short", "/api/users/me/login-methods/password", SetPasswordRequest("short"), "password"),
            arguments(
                "changed password too short", "/api/users/me/login-methods/password",
                ChangePasswordRequest("correct-horse-1", "short"), "newPassword",
            ),
            arguments(
                "password change with blank current password", "/api/users/me/login-methods/password",
                ChangePasswordRequest("", "brand-new-password"), "currentPassword",
            ),
            arguments("google link with blank token", "/api/users/me/login-methods/google", GoogleIdTokenRequest(""), "idToken"),
        )
    }
}
