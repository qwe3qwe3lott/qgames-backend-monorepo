package site.qgames.userauth.repository.user

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import site.qgames.userauth.mapper.user.UserRowMapper
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.model.user.UserModel
import site.qgames.userauth.table.user.UserTable
import site.qgames.userauth.util.newId
import java.time.Instant
import java.util.UUID

/** Таблица `user` (Exposed). Используется только из [UserRepository]. */
@Repository
@Transactional
class UserExposedRepository(private val userRowMapper: UserRowMapper) {

    fun insert(login: String, nickname: String, avatar: UserAvatar, now: Instant): UserModel {
        val id = newId()
        UserTable.insert {
            it[UserTable.id] = id
            it[UserTable.login] = login
            it[UserTable.nickname] = nickname
            it[UserTable.avatar] = avatar.name
            it[UserTable.createdAt] = now
        }
        return UserModel(id = id, login = login, nickname = nickname, avatar = avatar, createdAt = now)
    }

    fun updateProfile(id: UUID, nickname: String, avatar: UserAvatar): Int =
        UserTable.update({ UserTable.id eq id }) {
            it[UserTable.nickname] = nickname
            it[UserTable.avatar] = avatar.name
        }

    fun findById(id: UUID): UserModel? =
        UserTable.selectAll().where { UserTable.id eq id }.singleOrNull()?.let(userRowMapper::toUser)

    fun findByLogin(login: String): UserModel? =
        UserTable.selectAll().where { UserTable.login eq login }.singleOrNull()?.let(userRowMapper::toUser)
}
