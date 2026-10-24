package site.qgames.adminauth.controller.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.RestController
import site.qgames.adminauth.api.dto.admin.AdminResponse
import site.qgames.adminauth.api.dto.auth.LoginRequest
import site.qgames.adminauth.api.web.AuthApi
import site.qgames.adminauth.cookie.AuthCookies
import site.qgames.adminauth.mapper.admin.AdminResponseMapper
import site.qgames.adminauth.model.auth.AuthResultModel
import site.qgames.adminauth.service.auth.LoginService
import site.qgames.adminauth.service.auth.TokenService
import site.qgames.common.exception.exception.QgamesException
import site.qgames.common.exception.exception.UnauthorizedException

/**
 * Токены сессии администратора уходят клиенту только в HttpOnly-куках ([AuthCookies]), в теле ответа — данные
 * администратора. Запрос и ответ — request-scoped прокси, потому что контракт [AuthApi] не содержит servlet-параметров.
 */
@RestController
class AuthController(
    private val loginService: LoginService,
    private val tokenService: TokenService,
    private val adminResponseMapper: AdminResponseMapper,
    private val authCookies: AuthCookies,
    private val httpRequest: HttpServletRequest,
    private val httpResponse: HttpServletResponse,
) : AuthApi {

    override fun login(request: LoginRequest): AdminResponse =
        startSession(loginService.login(request.login, request.password))

    /** Без куки или с отклонённым токеном (401) обе куки удаляются. */
    override fun refresh(): AdminResponse {
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

    private fun startSession(auth: AuthResultModel): AdminResponse {
        authCookies.write(httpResponse, auth.tokens)
        return adminResponseMapper.toResponse(auth.admin)
    }
}
