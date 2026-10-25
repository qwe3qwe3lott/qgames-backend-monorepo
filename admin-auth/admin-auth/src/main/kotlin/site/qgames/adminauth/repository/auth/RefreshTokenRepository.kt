package site.qgames.adminauth.repository.auth

import org.springframework.stereotype.Repository
import site.qgames.adminauth.model.auth.RefreshTokenModel
import java.time.Instant
import java.util.UUID

@Repository
class RefreshTokenRepository(
    private val refreshTokenExposedRepository: RefreshTokenExposedRepository,
) {

    fun insert(adminId: UUID, familyId: UUID, tokenHash: String, expiresAt: Instant, now: Instant): RefreshTokenModel =
        refreshTokenExposedRepository.insert(adminId, familyId, tokenHash, expiresAt, now)

    fun findByTokenHash(tokenHash: String): RefreshTokenModel? = refreshTokenExposedRepository.findByTokenHash(tokenHash)

    /** Отзывает токен как заменённый новым. Возвращает 0, если токен уже отозван, в том числе параллельным запросом. */
    fun rotate(id: UUID, now: Instant): Int = refreshTokenExposedRepository.rotate(id, now)

    fun revokeFamily(familyId: UUID, now: Instant): Int = refreshTokenExposedRepository.revokeFamily(familyId, now)

    fun existsActiveInFamily(familyId: UUID): Boolean = refreshTokenExposedRepository.existsActiveInFamily(familyId)
}
