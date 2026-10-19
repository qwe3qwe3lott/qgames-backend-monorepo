package site.qgames.usergateway.publicapi.dto.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import site.qgames.usergateway.publicapi.dto.user.Avatar
import site.qgames.usergateway.publicapi.dto.user.NicknameRules
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class RegisterRequest(
    @field:NotBlank @field:Email @field:Size(min = 1, max = 320)
    val email: String,
    @field:NotBlank @field:Size(min = 8, max = 128)
    val password: String,
    @field:NotNull
    @field:Size(min = NicknameRules.MIN_LENGTH, max = NicknameRules.MAX_LENGTH)
    @field:Pattern(regexp = NicknameRules.PATTERN, message = NicknameRules.PATTERN_MESSAGE)
    val nickname: String,
    @field:NotNull
    val avatar: Avatar,
)

data class VerifyEmailRequest(
    @field:NotBlank @field:Email
    val email: String,
    @field:NotBlank @field:Pattern(regexp = "[0-9]{6}")
    val code: String,
)

data class ResendCodeRequest(
    @field:NotBlank @field:Email
    val email: String,
)

data class LoginRequest(
    @field:NotBlank @field:Email
    val email: String,
    @field:NotBlank
    val password: String,
)

data class GoogleIdTokenRequest(
    @field:NotBlank
    val idToken: String,
)
