package site.qgames.userauth.controller.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.RestController
import site.qgames.common.exception.exception.QgamesException
import site.qgames.common.exception.exception.UnauthorizedException
import site.qgames.userauth.api.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.api.dto.auth.GoogleRegisterRequest
import site.qgames.userauth.api.dto.auth.LoginRequest
import site.qgames.userauth.api.dto.user.UserResponse
import site.qgames.userauth.api.web.AuthApi
import site.qgames.userauth.cookie.AuthCookies
import site.qgames.userauth.mapper.user.UserResponseMapper
import site.qgames.userauth.model.auth.AuthResultModel
import site.qgames.userauth.service.auth.LoginService
import site.qgames.userauth.service.auth.TokenService

/**
 * Токены сессии уходят клиенту только в HttpOnly-куках ([AuthCookies]), в теле ответа — данные пользователя.
 * Запрос и ответ — request-scoped прокси, потому что контракт [AuthApi] не содержит servlet-параметров.
 */
@RestController
class AuthController(
    private val loginService: LoginService,
    private val tokenService: TokenService,
    private val userResponseMapper: UserResponseMapper,
    private val authCookies: AuthCookies,
    private val httpRequest: HttpServletRequest,
    private val httpResponse: HttpServletResponse,
) : AuthApi {

    override fun login(request: LoginRequest): UserResponse =
        startSession(loginService.loginWithPassword(request.login, request.password))

    override fun loginWithGoogle(request: GoogleIdTokenRequest): UserResponse =
        startSession(loginService.loginWithGoogle(request.idToken))

    override fun registerWithGoogle(request: GoogleRegisterRequest): UserResponse =
        startSession(loginService.registerWithGoogle(request.idToken, request.login, request.password))

    /** Без куки или с отклонённым токеном (401) обе куки удаляются. */
    override fun refresh(): UserResponse {
        val refreshToken = authCookies.refreshToken(httpRequest) ?: run {
            authCookies.clear(httpResponse)
            throw UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is missing")
        }
        val auth = try {
            loginService.refresh(refreshToken)
        } catch (ex: QgamesException) {
            if (ex is UnauthorizedException) {
                authCookies.clear(httpResponse)
            }
            throw ex
        }
        return startSession(auth)
    }

    override fun logout() {
        authCookies.refreshToken(httpRequest)?.let(tokenService::revokeSession)
        authCookies.clear(httpResponse)
    }

    private fun startSession(auth: AuthResultModel): UserResponse {
        authCookies.write(httpResponse, auth.tokens)
        return userResponseMapper.toResponse(auth.user)
    }
}
