package site.qgames.userauth.gatewayapi.dto.auth

import site.qgames.userauth.gatewayapi.dto.user.UserResponse
import java.time.Instant
import java.util.UUID

data class RegistrationResponse(
    val userId: UUID,
    val email: String,
)

data class AuthResponse(
    val user: UserResponse,
    val tokens: TokenPair,
)

data class TokenPair(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)
