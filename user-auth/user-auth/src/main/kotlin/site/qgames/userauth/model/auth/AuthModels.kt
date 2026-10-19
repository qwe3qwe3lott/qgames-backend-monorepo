package site.qgames.userauth.model.auth

import site.qgames.userauth.model.user.UserDetailsModel
import java.time.Instant
import java.util.UUID

/** Выпущенная пара токенов. Refresh-токен здесь в открытом виде — в БД хранится только его хеш. */
data class TokenPairModel(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)

/** Результат входа: пользователь и новая сессия. */
data class AuthResultModel(
    val user: UserDetailsModel,
    val tokens: TokenPairModel,
)

/** Результат регистрации: аккаунт создан, email ждёт подтверждения. */
data class RegistrationResultModel(
    val userId: UUID,
    val email: String,
)
