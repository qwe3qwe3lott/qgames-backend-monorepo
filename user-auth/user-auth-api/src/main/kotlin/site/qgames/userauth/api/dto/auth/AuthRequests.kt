package site.qgames.userauth.api.dto.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import site.qgames.userauth.api.dto.user.LoginRules
import site.qgames.userauth.api.dto.user.PasswordRules

/** Логин сравнивается без учёта регистра и пробелов по краям: сервер обрезает и приводит его к нижнему регистру. */
data class LoginRequest(
    @field:NotBlank
    val login: String,
    @field:NotBlank
    val password: String,
)

data class GoogleIdTokenRequest(
    @field:NotBlank
    val idToken: String,
)

/** Регистрация через Google: логин задаётся один раз и не меняется, пароль — по желанию (вход по логину и паролю). */
data class GoogleRegisterRequest(
    @field:NotBlank
    val idToken: String,
    @field:NotBlank
    @field:Size(min = LoginRules.MIN_LENGTH, max = LoginRules.MAX_LENGTH)
    @field:Pattern(regexp = LoginRules.PATTERN, message = LoginRules.PATTERN_MESSAGE)
    val login: String,
    @field:Size(min = PasswordRules.MIN_LENGTH, max = PasswordRules.MAX_LENGTH)
    val password: String? = null,
)
