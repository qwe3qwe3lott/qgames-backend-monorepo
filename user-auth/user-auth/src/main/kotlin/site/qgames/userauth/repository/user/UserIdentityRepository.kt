package site.qgames.userauth.repository.user

import org.springframework.data.repository.CrudRepository
import site.qgames.userauth.entity.user.IdentityProvider
import site.qgames.userauth.entity.user.UserIdentityEntity
import java.util.UUID

interface UserIdentityRepository : CrudRepository<UserIdentityEntity, UUID> {

    fun findByProviderAndSubject(provider: IdentityProvider, subject: String): UserIdentityEntity?

    fun findAllByUserId(userId: UUID): List<UserIdentityEntity>

    fun existsByUserIdAndProvider(userId: UUID, provider: IdentityProvider): Boolean
}
