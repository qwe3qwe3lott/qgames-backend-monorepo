package site.qgames.userauth.mapper.user

import org.jetbrains.exposed.v1.core.ResultRow
import org.springframework.stereotype.Component
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.model.user.UserIdentityModel
import site.qgames.userauth.model.user.UserModel
import site.qgames.userauth.table.user.UserIdentityTable
import site.qgames.userauth.table.user.UserTable

/** Строки таблиц `user` и `user_identity` → модели. */
@Component
class UserRowMapper {

    fun toUser(row: ResultRow) = UserModel(
        id = row[UserTable.id],
        login = row[UserTable.login],
        nickname = row[UserTable.nickname],
        avatar = UserAvatar.valueOf(row[UserTable.avatar]),
        createdAt = row[UserTable.createdAt],
    )

    fun toIdentity(row: ResultRow) = UserIdentityModel(
        id = row[UserIdentityTable.id],
        userId = row[UserIdentityTable.userId],
        provider = IdentityProvider.valueOf(row[UserIdentityTable.provider]),
        subject = row[UserIdentityTable.subject],
        passwordHash = row[UserIdentityTable.passwordHash],
        createdAt = row[UserIdentityTable.createdAt],
    )
}
