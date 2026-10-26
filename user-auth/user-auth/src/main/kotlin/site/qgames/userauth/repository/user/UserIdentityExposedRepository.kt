package site.qgames.userauth.repository.user

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import site.qgames.userauth.mapper.user.UserRowMapper
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.model.user.UserIdentityModel
import site.qgames.userauth.table.user.UserIdentityTable
import site.qgames.userauth.util.newId
import java.time.Instant
import java.util.UUID

/** Таблица `user_identity` (Exposed). Используется только из [UserIdentityRepository]. */
@Repository
@Transactional
class UserIdentityExposedRepository(private val userRowMapper: UserRowMapper) {

    fun insert(userId: UUID, provider: IdentityProvider, subject: String, passwordHash: String?, now: Instant): UserIdentityModel {
        val id = newId()
        UserIdentityTable.insert {
            it[UserIdentityTable.id] = id
            it[UserIdentityTable.userId] = userId
            it[UserIdentityTable.provider] = provider.name
            it[UserIdentityTable.subject] = subject
            it[UserIdentityTable.passwordHash] = passwordHash
            it[UserIdentityTable.createdAt] = now
        }
        return UserIdentityModel(id, userId, provider, subject, passwordHash, now)
    }

    fun findByProviderAndSubject(provider: IdentityProvider, subject: String): UserIdentityModel? =
        UserIdentityTable.selectAll()
            .where { (UserIdentityTable.provider eq provider.name) and (UserIdentityTable.subject eq subject) }
            .singleOrNull()
            ?.let(userRowMapper::toIdentity)

    fun findAllByUserId(userId: UUID): List<UserIdentityModel> =
        UserIdentityTable.selectAll().where { UserIdentityTable.userId eq userId }.map(userRowMapper::toIdentity)

    fun findByUserIdAndProvider(userId: UUID, provider: IdentityProvider): UserIdentityModel? =
        UserIdentityTable.selectAll()
            .where { (UserIdentityTable.userId eq userId) and (UserIdentityTable.provider eq provider.name) }
            .singleOrNull()
            ?.let(userRowMapper::toIdentity)

    fun updatePasswordHash(id: UUID, expectedHash: String, newHash: String): Int =
        UserIdentityTable.update({ (UserIdentityTable.id eq id) and (UserIdentityTable.passwordHash eq expectedHash) }) {
            it[UserIdentityTable.passwordHash] = newHash
        }
}
