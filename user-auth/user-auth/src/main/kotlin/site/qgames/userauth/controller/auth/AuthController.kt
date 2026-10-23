package site.qgames.userauth.controller.auth

import org.springframework.web.bind.annotation.RestController
import site.qgames.userauth.gatewayapi.api.AuthApi
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.auth.LoginRequest
import site.qgames.userauth.gatewayapi.dto.auth.RefreshTokenRequest
import site.qgames.userauth.mapper.auth.AuthResponseMapper
import site.qgames.userauth.service.auth.LoginService
import site.qgames.userauth.service.auth.TokenService

@RestController
class AuthController(
    private val loginService: LoginService,
    private val tokenService: TokenService,
    private val authResponseMapper: AuthResponseMapper,
) : AuthApi {

    override fun login(request: LoginRequest): AuthResponse =
        authResponseMapper.toResponse(loginService.loginWithPassword(request.email, request.password))

    override fun loginWithGoogle(request: GoogleIdTokenRequest): AuthResponse =
        authResponseMapper.toResponse(loginService.loginWithGoogle(request.idToken))

    override fun refresh(request: RefreshTokenRequest): AuthResponse =
        authResponseMapper.toResponse(loginService.refresh(request.refreshToken))

    override fun logout(request: RefreshTokenRequest) =
        tokenService.revokeSession(request.refreshToken)
}
