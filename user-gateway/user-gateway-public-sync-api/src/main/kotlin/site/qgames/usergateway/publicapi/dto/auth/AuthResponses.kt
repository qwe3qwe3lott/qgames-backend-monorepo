package site.qgames.usergateway.publicapi.dto.auth

import java.util.UUID

data class RegistrationResponse(
    val userId: UUID,
    val email: String,
)
