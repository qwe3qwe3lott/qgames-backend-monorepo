package site.qgames.userauth.gatewayapi.dto.user

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class UserResponse(
    val id: UUID,
    val email: String,
    val emailVerified: Boolean,
    val nickname: String,
    val avatar: Avatar,
    val loginMethods: List<LoginMethod>,
)

enum class LoginMethod {
    PASSWORD,
    GOOGLE,
}

/** Аватар из заранее определённого на фронте набора. */
enum class Avatar {
    CAT,
    DOG,
}

data class SetPasswordRequest(
    @field:NotBlank @field:Size(min = 8, max = 128)
    val password: String,
)

data class ChangePasswordRequest(
    @field:NotBlank
    val currentPassword: String,
    @field:NotBlank @field:Size(min = 8, max = 128)
    val newPassword: String,
)

/** Меняется только переданное; хотя бы одно поле обязательно. */
data class UpdateProfileRequest(
    @field:Size(min = NicknameRules.MIN_LENGTH, max = NicknameRules.MAX_LENGTH)
    @field:Pattern(regexp = NicknameRules.PATTERN, message = NicknameRules.PATTERN_MESSAGE)
    val nickname: String? = null,
    val avatar: Avatar? = null,
)

/** Ник не уникален: игроки различаются по id. */
object NicknameRules {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 32

    /** Без пробелов по краям и без управляющих символов. */
    const val PATTERN = "^[^\\s\\p{Cc}]([^\\p{Cc}]*[^\\s\\p{Cc}])?$"
    const val PATTERN_MESSAGE = "must not start or end with whitespace or contain control characters"
}
