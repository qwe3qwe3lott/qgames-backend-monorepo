package site.qgames.admingateway.controller.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClientResponseException
import site.qgames.adminauth.gatewayapi.api.AuthApi
import site.qgames.adminauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.adminauth.gatewayapi.dto.auth.RefreshTokenRequest
import site.qgames.admingateway.cookie.AuthCookies
import site.qgames.admingateway.mapper.admin.toPublic
import site.qgames.admingateway.mapper.auth.toAdminAuth
import site.qgames.admingateway.publicapi.api.PublicAuthApi
import site.qgames.admingateway.publicapi.dto.admin.AdminResponse
import site.qgames.admingateway.publicapi.dto.auth.AdminLoginRequest
import site.qgames.common.exception.exception.UnauthorizedException

/**
 * Публичные эндпоинты аутентификации администраторов. Токены из admin-auth уходят клиенту только в HttpOnly-куках,
 * в теле ответа — данные администратора. Запрос и ответ — request-scoped прокси, потому что контракт
 * [PublicAuthApi] не содержит servlet-параметров.
 */
@RestController
class AuthGatewayController(
    private val authApi: AuthApi,
    private val authCookies: AuthCookies,
    private val httpRequest: HttpServletRequest,
    private val httpResponse: HttpServletResponse,
) : PublicAuthApi {

    override fun login(request: AdminLoginRequest): AdminResponse =
        startSession(authApi.login(request.toAdminAuth()))

    override fun refresh(): AdminResponse {
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

    private fun startSession(auth: AuthResponse): AdminResponse {
        authCookies.write(httpResponse, auth.tokens)
        return auth.admin.toPublic()
    }
}
