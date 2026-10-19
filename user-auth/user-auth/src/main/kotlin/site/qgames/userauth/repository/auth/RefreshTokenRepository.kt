package site.qgames.userauth.repository.auth

import org.springframework.stereotype.Repository
import site.qgames.userauth.entity.auth.RefreshTokenEntity
import site.qgames.userauth.mapper.auth.AuthEntityMapper
import site.qgames.userauth.model.auth.RefreshTokenModel
import java.time.Instant
import java.util.UUID

@Repository
class RefreshTokenRepository(
    private val refreshTokenPostgresqlRepository: RefreshTokenPostgresqlRepository,
    private val authEntityMapper: AuthEntityMapper,
) {

    fun insert(userId: UUID, familyId: UUID, tokenHash: String, expiresAt: Instant, now: Instant): RefreshTokenModel =
        authEntityMapper.toModel(
            refreshTokenPostgresqlRepository.save(
                RefreshTokenEntity(userId = userId, familyId = familyId, tokenHash = tokenHash, expiresAt = expiresAt, createdAt = now),
            ),
        )

    fun findByTokenHash(tokenHash: String): RefreshTokenModel? =
        refreshTokenPostgresqlRepository.findByTokenHash(tokenHash)?.let(authEntityMapper::toModel)

    /** Отзывает токен как заменённый новым. Возвращает 0, если токен уже отозван, в том числе параллельным запросом. */
    fun rotate(id: UUID, now: Instant): Int = refreshTokenPostgresqlRepository.rotate(id, now)

    fun revokeFamily(familyId: UUID, now: Instant): Int = refreshTokenPostgresqlRepository.revokeFamily(familyId, now)

    fun existsActiveInFamily(familyId: UUID): Boolean = refreshTokenPostgresqlRepository.existsActiveInFamily(familyId)
}
