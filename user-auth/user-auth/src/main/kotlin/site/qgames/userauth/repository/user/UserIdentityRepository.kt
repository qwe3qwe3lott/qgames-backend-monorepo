package site.qgames.userauth.repository.user

import org.springframework.stereotype.Repository
import site.qgames.userauth.entity.user.UserIdentityEntity
import site.qgames.userauth.mapper.user.UserEntityMapper
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.model.user.UserIdentityModel
import java.time.Instant
import java.util.UUID

@Repository
class UserIdentityRepository(
    private val userIdentityPostgresqlRepository: UserIdentityPostgresqlRepository,
    private val userEntityMapper: UserEntityMapper,
) {

    fun insert(userId: UUID, provider: IdentityProvider, subject: String, passwordHash: String?, now: Instant): UserIdentityModel =
        userEntityMapper.toModel(
            userIdentityPostgresqlRepository.save(
                UserIdentityEntity(userId = userId, provider = provider.name, subject = subject, passwordHash = passwordHash, createdAt = now),
            ),
        )

    fun findByProviderAndSubject(provider: IdentityProvider, subject: String): UserIdentityModel? =
        userIdentityPostgresqlRepository.findByProviderAndSubject(provider.name, subject)?.let(userEntityMapper::toModel)

    fun findAllByUserId(userId: UUID): List<UserIdentityModel> =
        userIdentityPostgresqlRepository.findAllByUserId(userId).map(userEntityMapper::toModel)

    fun existsByUserIdAndProvider(userId: UUID, provider: IdentityProvider): Boolean =
        userIdentityPostgresqlRepository.existsByUserIdAndProvider(userId, provider.name)
}
