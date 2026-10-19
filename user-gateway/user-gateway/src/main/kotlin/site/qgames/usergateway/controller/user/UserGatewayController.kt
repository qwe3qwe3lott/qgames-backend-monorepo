package site.qgames.usergateway.controller.user

import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.RestController
import site.qgames.userauth.gatewayapi.api.UserApi
import site.qgames.usergateway.cookie.AuthCookies
import site.qgames.usergateway.mapper.auth.toUserAuth
import site.qgames.usergateway.mapper.user.toPublic
import site.qgames.usergateway.mapper.user.toUserAuth
import site.qgames.usergateway.publicapi.api.PublicUserApi
import site.qgames.usergateway.publicapi.dto.auth.GoogleIdTokenRequest
import site.qgames.usergateway.publicapi.dto.user.ChangePasswordRequest
import site.qgames.usergateway.publicapi.dto.user.SetPasswordRequest
import site.qgames.usergateway.publicapi.dto.user.UpdateProfileRequest
import site.qgames.usergateway.publicapi.dto.user.UserResponse

/**
 * Требует аутентификации. X-User-Id в user-auth проставляет UserIdPropagationInterceptor.
 * Ответ — request-scoped прокси: смена пароля выдаёт новую сессию, и её токены уходят в куки.
 */
@RestController
class UserGatewayController(
    private val userApi: UserApi,
    private val authCookies: AuthCookies,
    private val httpResponse: HttpServletResponse,
) : PublicUserApi {

    override fun getCurrentUser(): UserResponse = userApi.getCurrentUser().toPublic()

    override fun updateProfile(request: UpdateProfileRequest): UserResponse =
        userApi.updateProfile(request.toUserAuth()).toPublic()

    override fun addPasswordLogin(request: SetPasswordRequest): UserResponse =
        userApi.addPasswordLogin(request.toUserAuth()).toPublic()

    override fun changePassword(request: ChangePasswordRequest): UserResponse {
        val auth = userApi.changePassword(request.toUserAuth())
        authCookies.write(httpResponse, auth.tokens)
        return auth.user.toPublic()
    }

    override fun addGoogleLogin(request: GoogleIdTokenRequest): UserResponse =
        userApi.addGoogleLogin(request.toUserAuth()).toPublic()
}
