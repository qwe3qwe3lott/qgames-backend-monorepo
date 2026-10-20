package site.qgames.adminauth.controller.auth

import org.springframework.web.bind.annotation.RestController
import site.qgames.adminauth.gatewayapi.api.AuthApi
import site.qgames.adminauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.adminauth.gatewayapi.dto.auth.LoginRequest
import site.qgames.adminauth.gatewayapi.dto.auth.RefreshTokenRequest
import site.qgames.adminauth.mapper.auth.AuthResponseMapper
import site.qgames.adminauth.service.auth.LoginService
import site.qgames.adminauth.service.auth.TokenService

@RestController
class AuthController(
    private val loginService: LoginService,
    private val tokenService: TokenService,
    private val authResponseMapper: AuthResponseMapper,
) : AuthApi {

    override fun login(request: LoginRequest): AuthResponse =
        authResponseMapper.toResponse(loginService.login(request.login, request.password))

    override fun refresh(request: RefreshTokenRequest): AuthResponse =
        authResponseMapper.toResponse(loginService.refresh(request.refreshToken))

    override fun logout(request: RefreshTokenRequest) =
        tokenService.revokeSession(request.refreshToken)
}
