package site.qgames.userauth.repository.user

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import site.qgames.userauth.entity.user.UserIdentityEntity
import java.util.UUID

/** Таблица user_identity. Используется только из [UserIdentityRepository]. */
interface UserIdentityPostgresqlRepository : CrudRepository<UserIdentityEntity, UUID> {

    fun findByProviderAndSubject(provider: String, subject: String): UserIdentityEntity?

    fun findAllByUserId(userId: UUID): List<UserIdentityEntity>

    fun existsByUserIdAndProvider(userId: UUID, provider: String): Boolean

    fun findByUserIdAndProvider(userId: UUID, provider: String): UserIdentityEntity?

    /** Возвращает 0, если хеш уже сменил параллельный запрос. */
    @Modifying
    @Query("UPDATE \"user_auth#user_identity\" SET password_hash = :newHash WHERE id = :id AND password_hash = :expectedHash")
    fun updatePasswordHash(id: UUID, expectedHash: String, newHash: String): Int
}
