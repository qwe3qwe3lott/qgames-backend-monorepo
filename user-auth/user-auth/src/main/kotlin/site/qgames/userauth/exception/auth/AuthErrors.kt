package site.qgames.userauth.exception.auth

import site.qgames.common.exception.exception.ClientException
import site.qgames.common.exception.exception.ConflictException
import site.qgames.common.exception.exception.ErrorCodes
import site.qgames.common.exception.exception.NotFoundException
import site.qgames.common.exception.exception.UnauthorizedException

/**
 * Каталог ошибок аутентификации. Коды — часть контракта с фронтом, их нельзя переименовывать без миграции клиента.
 */
object AuthErrors {

    fun invalidCredentials() =
        UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password")

    fun invalidRefreshToken() =
        UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired")

    fun refreshTokenReused() =
        UnauthorizedException("REFRESH_TOKEN_REUSED", "Refresh token was already used, session is revoked")

    fun invalidGoogleToken(cause: Throwable? = null) =
        UnauthorizedException("INVALID_GOOGLE_TOKEN", "Google ID token is invalid", cause = cause)

    fun googleEmailNotVerified() =
        ClientException("GOOGLE_EMAIL_NOT_VERIFIED", "Google account email is not verified")

    fun accountLinkRequired() =
        ConflictException("ACCOUNT_LINK_REQUIRED", "Account with this email exists, sign in and link Google explicitly")

    fun loginMethodAlreadyLinked() =
        ConflictException("LOGIN_METHOD_ALREADY_LINKED", "This login method is already linked to the account")

    fun passwordNotSet() =
        ConflictException("PASSWORD_NOT_SET", "Password login is not set up for the account")

    fun invalidCurrentPassword() =
        ClientException("INVALID_CURRENT_PASSWORD", "Current password is invalid")

    fun googleAccountLinkedToAnotherUser() =
        ConflictException("GOOGLE_ACCOUNT_ALREADY_LINKED", "This Google account is linked to another user")

    fun nothingToUpdate() =
        ClientException(ErrorCodes.VALIDATION_ERROR, "Nothing to update")

    fun userNotFound() =
        NotFoundException("USER_NOT_FOUND", "User not found")
}
