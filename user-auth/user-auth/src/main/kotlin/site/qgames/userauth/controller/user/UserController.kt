package site.qgames.userauth.controller.user

import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.RestController
import site.qgames.common.web.context.RequestUserContext
import site.qgames.userauth.api.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.api.dto.user.ChangePasswordRequest
import site.qgames.userauth.api.dto.user.SetPasswordRequest
import site.qgames.userauth.api.dto.user.UpdateProfileRequest
import site.qgames.userauth.api.dto.user.UserResponse
import site.qgames.userauth.api.web.UserApi
import site.qgames.userauth.cookie.AuthCookies
import site.qgames.userauth.mapper.user.UserResponseMapper
import site.qgames.userauth.service.user.UserService

/**
 * Пользователь — из access-JWT ([RequestUserContext]). Ответ — request-scoped прокси: смена пароля выдаёт новую
 * сессию, и её токены уходят в куки.
 */
@RestController
class UserController(
    private val userService: UserService,
    private val userResponseMapper: UserResponseMapper,
    private val requestUserContext: RequestUserContext,
    private val authCookies: AuthCookies,
    private val httpResponse: HttpServletResponse,
) : UserApi {

    override fun getCurrentUser(): UserResponse =
        userResponseMapper.toResponse(userService.getUser(requestUserContext.userId()))

    override fun updateProfile(request: UpdateProfileRequest): UserResponse =
        userResponseMapper.toResponse(
            userService.updateProfile(requestUserContext.userId(), request.nickname, request.avatar?.let(userResponseMapper::toModel)),
        )

    override fun addPasswordLogin(request: SetPasswordRequest): UserResponse =
        userResponseMapper.toResponse(userService.addPasswordLogin(requestUserContext.userId(), request.password))

    override fun changePassword(request: ChangePasswordRequest): UserResponse {
        val auth = userService.changePassword(requestUserContext.userId(), request.currentPassword, request.newPassword)
        authCookies.write(httpResponse, auth.tokens)
        return userResponseMapper.toResponse(auth.user)
    }

    override fun addGoogleLogin(request: GoogleIdTokenRequest): UserResponse =
        userResponseMapper.toResponse(userService.addGoogleLogin(requestUserContext.userId(), request.idToken))
}
