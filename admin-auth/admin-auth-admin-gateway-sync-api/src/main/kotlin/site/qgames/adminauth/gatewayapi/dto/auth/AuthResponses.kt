package site.qgames.adminauth.gatewayapi.dto.auth

import site.qgames.adminauth.gatewayapi.dto.admin.AdminResponse
import java.time.Instant

/**
 * Открытая сессия: администратор и токены. Наружу не выходит: admin-gateway кладёт [tokens] в куки, а клиенту отдаёт
 * только [admin]. Поэтому [AuthResponse], [TokenPair] и [RefreshTokenRequest] не попадают в публичный OpenAPI.
 */
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
