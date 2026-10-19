package site.qgames.userauth.model.auth

import java.time.Instant
import java.util.UUID

data class EmailVerificationModel(
    val id: UUID,
    val userId: UUID,
    val codeHash: String,
    val expiresAt: Instant,
    val attempts: Int,
    val consumedAt: Instant?,
    val createdAt: Instant,
)
