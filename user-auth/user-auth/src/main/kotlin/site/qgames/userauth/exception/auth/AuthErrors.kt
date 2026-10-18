package site.qgames.userauth.exception.auth

import org.springframework.http.HttpStatus
import site.qgames.common.web.exception.ApiException

/**
 * Каталог ошибок аутентификации. Коды — часть контракта с фронтом, их нельзя переименовывать без миграции клиента.
 */
object AuthErrors {

    fun emailAlreadyRegistered() =
        ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email is already registered")

    fun emailAlreadyVerified() =
        ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_VERIFIED", "Email is already verified")

    fun emailNotVerified() =
        ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Email is not verified")

    fun invalidVerificationCode() =
        ApiException(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_CODE", "Verification code is invalid")

    fun verificationCodeExpired() =
        ApiException(HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_EXPIRED", "Verification code has expired")

    fun tooManyVerificationAttempts() =
        ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_VERIFICATION_ATTEMPTS", "Too many verification attempts, request a new code")

    fun resendCooldown() =
        ApiException(HttpStatus.TOO_MANY_REQUESTS, "RESEND_COOLDOWN", "Verification code was sent recently")

    fun invalidCredentials() =
        ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password")

    fun invalidRefreshToken() =
        ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired")

    fun refreshTokenReused() =
        ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED", "Refresh token was already used, session is revoked")

    fun invalidGoogleToken() =
        ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", "Google ID token is invalid")

    fun googleEmailNotVerified() =
        ApiException(HttpStatus.BAD_REQUEST, "GOOGLE_EMAIL_NOT_VERIFIED", "Google account email is not verified")

    fun accountLinkRequired() =
        ApiException(HttpStatus.CONFLICT, "ACCOUNT_LINK_REQUIRED", "Account with this email exists, sign in and link Google explicitly")

    fun loginMethodAlreadyLinked() =
        ApiException(HttpStatus.CONFLICT, "LOGIN_METHOD_ALREADY_LINKED", "This login method is already linked to the account")

    fun googleAccountLinkedToAnotherUser() =
        ApiException(HttpStatus.CONFLICT, "GOOGLE_ACCOUNT_ALREADY_LINKED", "This Google account is linked to another user")

    fun userNotFound() =
        ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found")
}
