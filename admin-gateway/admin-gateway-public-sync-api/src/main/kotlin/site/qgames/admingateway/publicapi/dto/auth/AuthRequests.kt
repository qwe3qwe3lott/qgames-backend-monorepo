package site.qgames.admingateway.publicapi.dto.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Логин сравнивается точно: без приведения регистра и обрезки пробелов. */
data class AdminLoginRequest(
    @field:NotBlank @field:Size(min = 1, max = 64)
    val login: String,
    @field:NotBlank @field:Size(min = 1, max = 128)
    val password: String,
)
