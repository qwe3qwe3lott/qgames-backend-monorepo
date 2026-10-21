package site.qgames.adminauth.exception.auth

import site.qgames.common.exception.exception.UnauthorizedException

/**
 * Каталог ошибок аутентификации администраторов. Коды — часть контракта с фронтом админки и совпадают с кодами
 * user-auth: на них держится общий клиентский код обновления сессии.
 */
object AuthErrors {

    fun invalidCredentials() =
        UnauthorizedException("INVALID_CREDENTIALS", "Invalid login or password")

    fun invalidRefreshToken() =
        UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired")

    fun refreshTokenReused() =
        UnauthorizedException("REFRESH_TOKEN_REUSED", "Refresh token was already used, session is revoked")
}
