package site.qgames.usergateway.controller.user

import org.springframework.web.bind.annotation.RestController
import site.qgames.userauth.gatewayapi.api.UserApi
import site.qgames.usergateway.mapper.auth.toUserAuth
import site.qgames.usergateway.mapper.user.toPublic
import site.qgames.usergateway.mapper.user.toUserAuth
import site.qgames.usergateway.publicapi.api.PublicUserApi
import site.qgames.usergateway.publicapi.dto.auth.GoogleIdTokenRequest
import site.qgames.usergateway.publicapi.dto.user.SetPasswordRequest
import site.qgames.usergateway.publicapi.dto.user.UpdateProfileRequest
import site.qgames.usergateway.publicapi.dto.user.UserResponse

/**
 * Требует аутентификации. X-User-Id в user-auth проставляет UserIdPropagationInterceptor.
 */
@RestController
class UserGatewayController(
    private val userApi: UserApi,
) : PublicUserApi {

    override fun getCurrentUser(): UserResponse = userApi.getCurrentUser().toPublic()

    override fun updateProfile(request: UpdateProfileRequest): UserResponse =
        userApi.updateProfile(request.toUserAuth()).toPublic()

    override fun addPasswordLogin(request: SetPasswordRequest): UserResponse =
        userApi.addPasswordLogin(request.toUserAuth()).toPublic()

    override fun addGoogleLogin(request: GoogleIdTokenRequest): UserResponse =
        userApi.addGoogleLogin(request.toUserAuth()).toPublic()
}
