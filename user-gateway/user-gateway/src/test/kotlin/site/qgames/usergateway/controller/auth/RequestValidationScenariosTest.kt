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
        "login, /api/auth/login, password",
        "login, /api/auth/login, email",
        "set password, /api/users/me/login-methods/password, password",
    )
    fun `request without a required field is rejected by gateway`(case: String, path: String, missingField: String) {
        val body = mutableMapOf<String, Any?>(
            "email" to "player@example.com",
            "password" to "correct-horse-1",
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
        private const val PROFILE_PATH = "/api/users/me/profile"

        @JvmStatic
        fun invalidRequests(): Stream<Arguments> = Stream.of(
            arguments("profile with too short nickname", PROFILE_PATH, UpdateProfileRequest(nickname = "ab"), "nickname"),
            arguments("profile with too long nickname", PROFILE_PATH, UpdateProfileRequest(nickname = "n".repeat(33)), "nickname"),
            arguments("profile with padded nickname", PROFILE_PATH, UpdateProfileRequest(nickname = " Bob "), "nickname"),
            arguments("profile with nickname \" ab\"", PROFILE_PATH, UpdateProfileRequest(nickname = " ab"), "nickname"),
            arguments("profile with nickname \"ab \"", PROFILE_PATH, UpdateProfileRequest(nickname = "ab "), "nickname"),
            arguments("profile with control character in nickname", PROFILE_PATH, UpdateProfileRequest(nickname = "a\u0007bc"), "nickname"),
            arguments("login with malformed email", "/api/auth/login", LoginRequest("not-an-email", "correct-horse-1"), "email"),
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
