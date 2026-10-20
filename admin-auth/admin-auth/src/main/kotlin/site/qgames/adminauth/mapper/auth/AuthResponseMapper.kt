package site.qgames.adminauth.mapper.auth

import org.springframework.stereotype.Component
import site.qgames.adminauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.adminauth.gatewayapi.dto.auth.TokenPair
import site.qgames.adminauth.mapper.admin.AdminResponseMapper
import site.qgames.adminauth.model.auth.AuthResultModel
import site.qgames.adminauth.model.auth.TokenPairModel

/** Модели аутентификации → DTO контракта для gateway. */
@Component
class AuthResponseMapper(private val adminResponseMapper: AdminResponseMapper) {

    fun toResponse(result: AuthResultModel) = AuthResponse(
        admin = adminResponseMapper.toResponse(result.admin),
        tokens = toResponse(result.tokens),
    )

    private fun toResponse(tokens: TokenPairModel) = TokenPair(
        accessToken = tokens.accessToken,
        accessTokenExpiresAt = tokens.accessTokenExpiresAt,
        refreshToken = tokens.refreshToken,
        refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
    )
}
