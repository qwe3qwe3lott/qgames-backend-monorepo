package site.qgames.usergateway.controller.user

import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.RestController
import site.qgames.userauth.gatewayapi.api.UserApi
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.user.ChangePasswordRequest
import site.qgames.userauth.gatewayapi.dto.user.SetPasswordRequest
import site.qgames.userauth.gatewayapi.dto.user.UpdateProfileRequest
import site.qgames.userauth.gatewayapi.dto.user.UserResponse
import site.qgames.usergateway.cookie.AuthCookies
import site.qgames.usergateway.publicapi.api.PublicUserApi

/**
 * Требует аутентификации. X-User-Id в user-auth проставляет UserIdPropagationInterceptor. Запросы и ответы — DTO
 * контракта user-auth, они передаются как есть. Ответ — request-scoped прокси: смена пароля выдаёт новую сессию, и её
 * токены уходят в куки.
 */
@RestController
class UserGatewayController(
    private val userApi: UserApi,
    private val authCookies: AuthCookies,
    private val httpResponse: HttpServletResponse,
) : PublicUserApi {

    override fun getCurrentUser(): UserResponse = userApi.getCurrentUser()

    override fun updateProfile(request: UpdateProfileRequest): UserResponse = userApi.updateProfile(request)

    override fun addPasswordLogin(request: SetPasswordRequest): UserResponse = userApi.addPasswordLogin(request)

    override fun changePassword(request: ChangePasswordRequest): UserResponse {
        val auth = userApi.changePassword(request)
        authCookies.write(httpResponse, auth.tokens)
        return auth.user
    }

    override fun addGoogleLogin(request: GoogleIdTokenRequest): UserResponse = userApi.addGoogleLogin(request)
}
