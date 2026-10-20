package site.qgames.adminauth.gatewayapi.dto.auth

import site.qgames.adminauth.gatewayapi.dto.admin.AdminResponse
import java.time.Instant

data class AuthResponse(
    val admin: AdminResponse,
    val tokens: TokenPair,
)

data class TokenPair(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)
