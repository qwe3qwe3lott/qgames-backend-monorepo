package site.qgames.userauth.repository.auth

import org.springframework.stereotype.Repository
import site.qgames.userauth.model.auth.RefreshTokenModel
import java.time.Instant
import java.util.UUID

@Repository
class RefreshTokenRepository(
    private val refreshTokenExposedRepository: RefreshTokenExposedRepository,
) {

    fun insert(userId: UUID, familyId: UUID, tokenHash: String, expiresAt: Instant, now: Instant): RefreshTokenModel =
        refreshTokenExposedRepository.insert(userId, familyId, tokenHash, expiresAt, now)

    fun findByTokenHash(tokenHash: String): RefreshTokenModel? = refreshTokenExposedRepository.findByTokenHash(tokenHash)

    /** Отзывает токен как заменённый новым. Возвращает 0, если токен уже отозван, в том числе параллельным запросом. */
    fun rotate(id: UUID, now: Instant): Int = refreshTokenExposedRepository.rotate(id, now)

    fun revokeFamily(familyId: UUID, now: Instant): Int = refreshTokenExposedRepository.revokeFamily(familyId, now)

    fun revokeAllByUserId(userId: UUID, now: Instant): Int = refreshTokenExposedRepository.revokeAllByUserId(userId, now)

    fun existsActiveInFamily(familyId: UUID): Boolean = refreshTokenExposedRepository.existsActiveInFamily(familyId)
}
