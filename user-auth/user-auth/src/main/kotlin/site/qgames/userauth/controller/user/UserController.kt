package site.qgames.userauth.controller.user

import org.springframework.web.bind.annotation.RestController
import site.qgames.common.web.context.RequestUserContext
import site.qgames.userauth.gatewayapi.api.UserApi
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.user.SetPasswordRequest
import site.qgames.userauth.gatewayapi.dto.user.UpdateProfileRequest
import site.qgames.userauth.gatewayapi.dto.user.UserResponse
import site.qgames.userauth.mapper.user.UserResponseMapper
import site.qgames.userauth.service.user.UserService

@RestController
class UserController(
    private val userService: UserService,
    private val userResponseMapper: UserResponseMapper,
    private val requestUserContext: RequestUserContext,
) : UserApi {

    override fun getCurrentUser(): UserResponse =
        userResponseMapper.toResponse(userService.getUser(requestUserContext.userId()))

    override fun updateProfile(request: UpdateProfileRequest): UserResponse =
        userResponseMapper.toResponse(
            userService.updateProfile(requestUserContext.userId(), request.nickname, request.avatar?.let(userResponseMapper::toModel)),
        )

    override fun addPasswordLogin(request: SetPasswordRequest): UserResponse =
        userResponseMapper.toResponse(userService.addPasswordLogin(requestUserContext.userId(), request.password))

    override fun addGoogleLogin(request: GoogleIdTokenRequest): UserResponse =
        userResponseMapper.toResponse(userService.addGoogleLogin(requestUserContext.userId(), request.idToken))
}
