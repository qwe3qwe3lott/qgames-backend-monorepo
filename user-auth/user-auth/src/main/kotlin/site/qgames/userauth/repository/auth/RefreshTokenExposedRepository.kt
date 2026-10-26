package site.qgames.userauth.repository.auth

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import site.qgames.userauth.mapper.auth.AuthRowMapper
import site.qgames.userauth.model.auth.RefreshTokenModel
import site.qgames.userauth.table.auth.RefreshTokenTable
import site.qgames.userauth.util.newId
import java.time.Instant
import java.util.UUID

/** Таблица `user_refresh_token` (Exposed). Используется только из [RefreshTokenRepository]. */
@Repository
@Transactional
class RefreshTokenExposedRepository(private val authRowMapper: AuthRowMapper) {

    fun insert(userId: UUID, familyId: UUID, tokenHash: String, expiresAt: Instant, now: Instant): RefreshTokenModel {
        val id = newId()
        RefreshTokenTable.insert {
            it[RefreshTokenTable.id] = id
            it[RefreshTokenTable.userId] = userId
            it[RefreshTokenTable.familyId] = familyId
            it[RefreshTokenTable.tokenHash] = tokenHash
            it[RefreshTokenTable.expiresAt] = expiresAt
            it[RefreshTokenTable.createdAt] = now
        }
        return RefreshTokenModel(id, userId, familyId, tokenHash, expiresAt, revokedAt = null, rotatedAt = null, createdAt = now)
    }

    fun findByTokenHash(tokenHash: String): RefreshTokenModel? =
        RefreshTokenTable.selectAll().where { RefreshTokenTable.tokenHash eq tokenHash }.singleOrNull()?.let(authRowMapper::toRefreshToken)

    fun rotate(id: UUID, now: Instant): Int =
        RefreshTokenTable.update({ (RefreshTokenTable.id eq id) and RefreshTokenTable.revokedAt.isNull() }) {
            it[RefreshTokenTable.revokedAt] = now
            it[RefreshTokenTable.rotatedAt] = now
        }

    fun revokeFamily(familyId: UUID, now: Instant): Int =
        RefreshTokenTable.update({ (RefreshTokenTable.familyId eq familyId) and RefreshTokenTable.revokedAt.isNull() }) {
            it[RefreshTokenTable.revokedAt] = now
        }

    fun revokeAllByUserId(userId: UUID, now: Instant): Int =
        RefreshTokenTable.update({ (RefreshTokenTable.userId eq userId) and RefreshTokenTable.revokedAt.isNull() }) {
            it[RefreshTokenTable.revokedAt] = now
        }

    fun existsActiveInFamily(familyId: UUID): Boolean =
        !RefreshTokenTable.selectAll()
            .where { (RefreshTokenTable.familyId eq familyId) and RefreshTokenTable.revokedAt.isNull() }
            .limit(1)
            .empty()
}
