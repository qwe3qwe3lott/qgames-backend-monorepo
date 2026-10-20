package site.qgames.adminauth.repository.auth

import org.springframework.stereotype.Repository
import site.qgames.adminauth.entity.auth.RefreshTokenEntity
import site.qgames.adminauth.mapper.auth.RefreshTokenEntityMapper
import site.qgames.adminauth.model.auth.RefreshTokenModel
import java.time.Instant
import java.util.UUID

@Repository
class RefreshTokenRepository(
    private val refreshTokenPostgresqlRepository: RefreshTokenPostgresqlRepository,
    private val refreshTokenEntityMapper: RefreshTokenEntityMapper,
) {

    fun insert(adminId: UUID, familyId: UUID, tokenHash: String, expiresAt: Instant, now: Instant): RefreshTokenModel =
        refreshTokenEntityMapper.toModel(
            refreshTokenPostgresqlRepository.save(
                RefreshTokenEntity(adminId = adminId, familyId = familyId, tokenHash = tokenHash, expiresAt = expiresAt, createdAt = now),
            ),
        )

    fun findByTokenHash(tokenHash: String): RefreshTokenModel? =
        refreshTokenPostgresqlRepository.findByTokenHash(tokenHash)?.let(refreshTokenEntityMapper::toModel)

    /** Отзывает токен как заменённый новым. Возвращает 0, если токен уже отозван, в том числе параллельным запросом. */
    fun rotate(id: UUID, now: Instant): Int = refreshTokenPostgresqlRepository.rotate(id, now)

    fun revokeFamily(familyId: UUID, now: Instant): Int = refreshTokenPostgresqlRepository.revokeFamily(familyId, now)

    fun existsActiveInFamily(familyId: UUID): Boolean = refreshTokenPostgresqlRepository.existsActiveInFamily(familyId)
}
