package site.qgames.userauth.repository.user

import org.springframework.stereotype.Repository
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.model.user.UserIdentityModel
import java.time.Instant
import java.util.UUID

@Repository
class UserIdentityRepository(
    private val userIdentityExposedRepository: UserIdentityExposedRepository,
) {

    fun insert(userId: UUID, provider: IdentityProvider, subject: String, passwordHash: String?, now: Instant): UserIdentityModel =
        userIdentityExposedRepository.insert(userId, provider, subject, passwordHash, now)

    fun findByProviderAndSubject(provider: IdentityProvider, subject: String): UserIdentityModel? =
        userIdentityExposedRepository.findByProviderAndSubject(provider, subject)

    fun findAllByUserId(userId: UUID): List<UserIdentityModel> = userIdentityExposedRepository.findAllByUserId(userId)

    fun existsByUserIdAndProvider(userId: UUID, provider: IdentityProvider): Boolean =
        userIdentityExposedRepository.findByUserIdAndProvider(userId, provider) != null

    fun findByUserIdAndProvider(userId: UUID, provider: IdentityProvider): UserIdentityModel? =
        userIdentityExposedRepository.findByUserIdAndProvider(userId, provider)

    /** Возвращает 0, если хеш уже сменил параллельный запрос. */
    fun updatePasswordHash(id: UUID, expectedHash: String, newHash: String): Int =
        userIdentityExposedRepository.updatePasswordHash(id, expectedHash, newHash)
}
