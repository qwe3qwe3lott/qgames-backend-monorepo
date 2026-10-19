package site.qgames.userauth.repository.user

import org.springframework.data.repository.CrudRepository
import site.qgames.userauth.entity.user.UserIdentityEntity
import java.util.UUID

/** Таблица user_identity. Используется только из [UserIdentityRepository]. */
interface UserIdentityPostgresqlRepository : CrudRepository<UserIdentityEntity, UUID> {

    fun findByProviderAndSubject(provider: String, subject: String): UserIdentityEntity?

    fun findAllByUserId(userId: UUID): List<UserIdentityEntity>

    fun existsByUserIdAndProvider(userId: UUID, provider: String): Boolean
}
