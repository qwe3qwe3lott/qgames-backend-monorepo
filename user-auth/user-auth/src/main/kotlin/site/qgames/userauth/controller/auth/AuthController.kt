package site.qgames.userauth.controller.auth

import org.springframework.web.bind.annotation.RestController
import site.qgames.userauth.gatewayapi.api.AuthApi
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.auth.LoginRequest
import site.qgames.userauth.gatewayapi.dto.auth.RefreshTokenRequest
import site.qgames.userauth.gatewayapi.dto.auth.RegisterRequest
import site.qgames.userauth.gatewayapi.dto.auth.RegistrationResponse
import site.qgames.userauth.gatewayapi.dto.auth.ResendCodeRequest
import site.qgames.userauth.gatewayapi.dto.auth.VerifyEmailRequest
import site.qgames.userauth.mapper.auth.AuthResponseMapper
import site.qgames.userauth.mapper.user.UserResponseMapper
import site.qgames.userauth.service.auth.LoginService
import site.qgames.userauth.service.auth.RegistrationService
import site.qgames.userauth.service.auth.TokenService

@RestController
class AuthController(
    private val registrationService: RegistrationService,
    private val loginService: LoginService,
    private val tokenService: TokenService,
    private val authResponseMapper: AuthResponseMapper,
    private val userResponseMapper: UserResponseMapper,
) : AuthApi {

    override fun register(request: RegisterRequest): RegistrationResponse =
        authResponseMapper.toResponse(
            registrationService.register(
                rawEmail = request.email,
                password = request.password,
                nickname = request.nickname,
                avatar = userResponseMapper.toModel(request.avatar),
            ),
        )

    override fun verifyEmail(request: VerifyEmailRequest): AuthResponse =
        authResponseMapper.toResponse(registrationService.verifyEmail(request.email, request.code))

    override fun resendCode(request: ResendCodeRequest) =
        registrationService.resendCode(request.email)

    override fun login(request: LoginRequest): AuthResponse =
        authResponseMapper.toResponse(loginService.loginWithPassword(request.email, request.password))

    override fun loginWithGoogle(request: GoogleIdTokenRequest): AuthResponse =
        authResponseMapper.toResponse(loginService.loginWithGoogle(request.idToken))

    override fun refresh(request: RefreshTokenRequest): AuthResponse =
        authResponseMapper.toResponse(loginService.refresh(request.refreshToken))

    override fun logout(request: RefreshTokenRequest) =
        tokenService.revokeSession(request.refreshToken)
}
