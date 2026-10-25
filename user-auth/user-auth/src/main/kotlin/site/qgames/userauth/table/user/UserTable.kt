package site.qgames.userauth.table.user

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Таблица `user` — аккаунты игроков. Полное описание для user-auth; другие модули читают публичный профиль через
 * UserProfileTable из user-auth-api. Аватар — имя enum UserAvatar.
 */
object UserTable : Table("user") {
    val id = javaUUID("id")
    /** Логин входа по паролю (email Google-аккаунта, которым создан аккаунт), уникален. */
    val email = varchar("email", 320)
    /** Не уникален: игроки различаются по id. */
    val nickname = varchar("nickname", 32)
    val avatar = varchar("avatar", 16)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
