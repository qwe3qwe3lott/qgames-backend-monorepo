package site.qgames.userauth.exception.auth

import site.qgames.common.exception.exception.ClientException
import site.qgames.common.exception.exception.ConflictException
import site.qgames.common.exception.exception.ErrorCodes
import site.qgames.common.exception.exception.ForbiddenException
import site.qgames.common.exception.exception.NotFoundException
import site.qgames.common.exception.exception.TooManyRequestsException
import site.qgames.common.exception.exception.UnauthorizedException

/**
 * Каталог ошибок аутентификации. Коды — часть контракта с фронтом, их нельзя переименовывать без миграции клиента.
 */
object AuthErrors {

    fun emailAlreadyRegistered() =
        ConflictException("EMAIL_ALREADY_REGISTERED", "Email is already registered")

    fun emailAlreadyVerified() =
        ConflictException("EMAIL_ALREADY_VERIFIED", "Email is already verified")

    fun emailNotVerified() =
        ForbiddenException("EMAIL_NOT_VERIFIED", "Email is not verified")

    fun invalidVerificationCode() =
        ClientException("INVALID_VERIFICATION_CODE", "Verification code is invalid")

    fun verificationCodeExpired() =
        ClientException("VERIFICATION_CODE_EXPIRED", "Verification code has expired")

    fun tooManyVerificationAttempts() =
        TooManyRequestsException("TOO_MANY_VERIFICATION_ATTEMPTS", "Too many verification attempts, request a new code")

    fun resendCooldown() =
        TooManyRequestsException("RESEND_COOLDOWN", "Verification code was sent recently")

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

    fun googleAccountLinkedToAnotherUser() =
        ConflictException("GOOGLE_ACCOUNT_ALREADY_LINKED", "This Google account is linked to another user")

    fun nothingToUpdate() =
        ClientException(ErrorCodes.VALIDATION_ERROR, "Nothing to update")

    fun userNotFound() =
        NotFoundException("USER_NOT_FOUND", "User not found")
}
