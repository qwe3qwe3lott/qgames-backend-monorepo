package site.qgames.adminauth.model.auth

import java.time.Instant
import java.util.UUID

/**
 * Хранится только SHA-256 токена. Все токены одной цепочки ротаций имеют общий [familyId].
 * [rotatedAt] заполнен, если токен отозван из-за замены новым, а не при выходе или обнаружении кражи.
 */
data class RefreshTokenModel(
    val id: UUID,
    val adminId: UUID,
    val familyId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val rotatedAt: Instant?,
    val createdAt: Instant,
)
