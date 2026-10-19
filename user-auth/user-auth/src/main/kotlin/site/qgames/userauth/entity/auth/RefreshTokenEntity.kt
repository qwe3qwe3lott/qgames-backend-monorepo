package site.qgames.userauth.entity.auth

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/** Строка таблицы refresh_token. */
@Table("refresh_token")
data class RefreshTokenEntity(
    @Id
    val id: UUID? = null,
    val userId: UUID,
    val familyId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
    val rotatedAt: Instant? = null,
    val createdAt: Instant,
)
