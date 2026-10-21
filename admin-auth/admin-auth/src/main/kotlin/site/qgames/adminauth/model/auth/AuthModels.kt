package site.qgames.adminauth.model.auth

import site.qgames.adminauth.model.admin.AdminModel
import java.time.Instant

/** Выпущенная пара токенов. Refresh-токен здесь в открытом виде — в БД хранится только его хеш. */
data class TokenPairModel(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)

/** Результат входа или обновления сессии: администратор и токены. */
data class AuthResultModel(
    val admin: AdminModel,
    val tokens: TokenPairModel,
)
