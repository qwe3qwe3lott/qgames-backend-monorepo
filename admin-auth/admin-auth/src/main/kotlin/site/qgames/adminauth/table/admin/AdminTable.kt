package site.qgames.adminauth.table.admin

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Таблица `admin` — администраторы. */
object AdminTable : Table("admin") {
    val id = javaUUID("id")
    /** Уникален, сравнивается точно, с учётом регистра. */
    val login = varchar("login", 64)
    /** Argon2id, соль внутри хеша. */
    val passwordHash = varchar("password_hash", 255)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
