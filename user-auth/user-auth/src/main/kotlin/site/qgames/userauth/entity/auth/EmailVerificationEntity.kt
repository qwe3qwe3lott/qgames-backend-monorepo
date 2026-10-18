package site.qgames.userauth.entity.auth

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("email_verification")
data class EmailVerificationEntity(
    @Id
    val id: UUID? = null,
    val userId: UUID,
    val codeHash: String,
    val expiresAt: Instant,
    val attempts: Int = 0,
    val consumedAt: Instant? = null,
    val createdAt: Instant,
)
