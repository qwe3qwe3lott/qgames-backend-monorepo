package site.qgames.usergateway.publicapi.dto.user

import jakarta.validation.constraints.Size
import java.util.UUID

data class UserResponse(
    val id: UUID,
    val email: String,
    val emailVerified: Boolean,
    val loginMethods: List<LoginMethod>,
)

enum class LoginMethod {
    PASSWORD,
    GOOGLE,
}

data class SetPasswordRequest(
    @field:Size(min = 8, max = 128)
    val password: String,
)
