package site.qgames.usergateway.controller.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClientResponseException
import site.qgames.common.exception.exception.UnauthorizedException
import site.qgames.userauth.gatewayapi.api.AuthApi
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.gatewayapi.dto.auth.RefreshTokenRequest
import site.qgames.usergateway.cookie.AuthCookies
import site.qgames.usergateway.mapper.auth.toPublic
import site.qgames.usergateway.mapper.auth.toUserAuth
import site.qgames.usergateway.mapper.user.toPublic
import site.qgames.usergateway.publicapi.api.PublicAuthApi
import site.qgames.usergateway.publicapi.dto.auth.GoogleIdTokenRequest
import site.qgames.usergateway.publicapi.dto.auth.LoginRequest
import site.qgames.usergateway.publicapi.dto.auth.RegisterRequest
import site.qgames.usergateway.publicapi.dto.auth.RegistrationResponse
import site.qgames.usergateway.publicapi.dto.auth.ResendCodeRequest
import site.qgames.usergateway.publicapi.dto.auth.VerifyEmailRequest
import site.qgames.usergateway.publicapi.dto.user.UserResponse

/**
 * Публичные эндпоинты аутентификации. Токены из user-auth уходят клиенту только в HttpOnly-куках,
 * в теле ответа — данные пользователя. Запрос и ответ — request-scoped прокси, потому что контракт
 * [PublicAuthApi] не содержит servlet-параметров.
 */
@RestController
class AuthGatewayController(
    private val authApi: AuthApi,
    private val authCookies: AuthCookies,
    private val httpRequest: HttpServletRequest,
    private val httpResponse: HttpServletResponse,
) : PublicAuthApi {

    override fun register(request: RegisterRequest): RegistrationResponse =
        authApi.register(request.toUserAuth()).toPublic()

    override fun verifyEmail(request: VerifyEmailRequest): UserResponse =
        startSession(authApi.verifyEmail(request.toUserAuth()))

    override fun resendCode(request: ResendCodeRequest) =
        authApi.resendCode(request.toUserAuth())

    override fun login(request: LoginRequest): UserResponse =
        startSession(authApi.login(request.toUserAuth()))

    override fun loginWithGoogle(request: GoogleIdTokenRequest): UserResponse =
        startSession(authApi.loginWithGoogle(request.toUserAuth()))

    override fun refresh(): UserResponse {
        val refreshToken = authCookies.refreshToken(httpRequest) ?: run {
            authCookies.clear(httpResponse)
            throw UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is missing")
        }
        val auth = try {
            authApi.refresh(RefreshTokenRequest(refreshToken))
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                authCookies.clear(httpResponse)
            }
            throw ex
        }
        return startSession(auth)
    }

    override fun logout() {
        authCookies.refreshToken(httpRequest)?.let { authApi.logout(RefreshTokenRequest(it)) }
        authCookies.clear(httpResponse)
    }

    private fun startSession(auth: AuthResponse): UserResponse {
        authCookies.write(httpResponse, auth.tokens)
        return auth.user.toPublic()
    }
}
