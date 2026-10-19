package site.qgames.userauth.mapper.auth

import org.springframework.stereotype.Component
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.gatewayapi.dto.auth.RegistrationResponse
import site.qgames.userauth.gatewayapi.dto.auth.TokenPair
import site.qgames.userauth.mapper.user.UserResponseMapper
import site.qgames.userauth.model.auth.AuthResultModel
import site.qgames.userauth.model.auth.RegistrationResultModel
import site.qgames.userauth.model.auth.TokenPairModel

/** Модели аутентификации → DTO контракта для gateway. */
@Component
class AuthResponseMapper(private val userResponseMapper: UserResponseMapper) {

    fun toResponse(result: AuthResultModel) = AuthResponse(
        user = userResponseMapper.toResponse(result.user),
        tokens = toResponse(result.tokens),
    )

    fun toResponse(result: RegistrationResultModel) = RegistrationResponse(userId = result.userId, email = result.email)

    private fun toResponse(tokens: TokenPairModel) = TokenPair(
        accessToken = tokens.accessToken,
        accessTokenExpiresAt = tokens.accessTokenExpiresAt,
        refreshToken = tokens.refreshToken,
        refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
    )
}
