package site.qgames.userauth.entity.auth

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * Хранится только SHA-256 токена. Все токены одной цепочки ротаций имеют общий [familyId].
 * [rotatedAt] заполнен, если токен отозван из-за замены новым, а не при выходе или обнаружении кражи.
 */
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
