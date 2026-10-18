package site.qgames.usergateway.controller.auth

import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import jakarta.servlet.http.Cookie
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import site.qgames.usergateway.BaseIntegrationTest
import site.qgames.usergateway.publicapi.dto.auth.GoogleIdTokenRequest
import site.qgames.usergateway.publicapi.dto.auth.LoginRequest
import site.qgames.usergateway.publicapi.dto.auth.RegisterRequest
import site.qgames.usergateway.publicapi.dto.auth.ResendCodeRequest
import site.qgames.usergateway.publicapi.dto.auth.VerifyEmailRequest
import site.qgames.usergateway.publicapi.dto.user.SetPasswordRequest
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
        mockMvc.post(path) {
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

    companion object {
        @JvmStatic
        fun invalidRequests(): Stream<Arguments> = Stream.of(
            arguments("register with malformed email", "/api/auth/register", RegisterRequest("not-an-email", "correct-horse-1"), "email"),
            arguments("register with short password", "/api/auth/register", RegisterRequest("player@example.com", "short"), "password"),
            arguments("verification code is not 6 digits", "/api/auth/verify-email", VerifyEmailRequest("player@example.com", "12ab"), "code"),
            arguments("resend for blank email", "/api/auth/resend-code", ResendCodeRequest(""), "email"),
            arguments("login with blank password", "/api/auth/login", LoginRequest("player@example.com", ""), "password"),
            arguments("google login with blank token", "/api/auth/google", GoogleIdTokenRequest(""), "idToken"),
            arguments("new password too short", "/api/users/me/login-methods/password", SetPasswordRequest("short"), "password"),
            arguments("google link with blank token", "/api/users/me/login-methods/google", GoogleIdTokenRequest(""), "idToken"),
        )
    }
}
