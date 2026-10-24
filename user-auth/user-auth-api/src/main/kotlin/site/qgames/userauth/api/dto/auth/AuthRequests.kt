package site.qgames.userauth.api.dto.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

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
