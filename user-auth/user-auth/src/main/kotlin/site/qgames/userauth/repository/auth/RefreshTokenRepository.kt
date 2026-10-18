package site.qgames.userauth.repository.auth

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import site.qgames.userauth.entity.auth.RefreshTokenEntity
import java.time.Instant
import java.util.UUID

interface RefreshTokenRepository : CrudRepository<RefreshTokenEntity, UUID> {

    fun findByTokenHash(tokenHash: String): RefreshTokenEntity?

    /** Отзывает токен как заменённый новым. Возвращает 0, если токен уже отозван, в том числе параллельным запросом. */
    @Modifying
    @Query("UPDATE refresh_token SET revoked_at = :now, rotated_at = :now WHERE id = :id AND revoked_at IS NULL")
    fun rotate(id: UUID, now: Instant): Int

    @Modifying
    @Query("UPDATE refresh_token SET revoked_at = :now WHERE family_id = :familyId AND revoked_at IS NULL")
    fun revokeFamily(familyId: UUID, now: Instant): Int

    @Query("SELECT EXISTS (SELECT 1 FROM refresh_token WHERE family_id = :familyId AND revoked_at IS NULL)")
    fun existsActiveInFamily(familyId: UUID): Boolean
}
