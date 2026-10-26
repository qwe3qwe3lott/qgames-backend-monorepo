package site.qgames.userauth.table.user

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Таблица `user_identity` — способы входа: (provider, subject) уникальна, у пользователя — один способ каждого вида. */
object UserIdentityTable : Table("user_identity") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    /** Имя enum IdentityProvider. */
    val provider = varchar("provider", 32)
    /** PASSWORD — логин пользователя (не меняется), GOOGLE — claim `sub` Google ID token. */
    val subject = varchar("subject", 320)
    val passwordHash = varchar("password_hash", 255).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
