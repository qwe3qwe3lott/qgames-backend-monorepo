package site.qgames.adminauth.table.auth

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Таблица `admin_refresh_token` — refresh-токены администраторов (хранится только SHA-256). */
object RefreshTokenTable : Table("admin_refresh_token") {
    val id = javaUUID("id")
    val adminId = javaUUID("admin_id")
    /** Общий у всех токенов одной цепочки ротаций. */
    val familyId = javaUUID("family_id")
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()
    /** Заполняется при замене токена новым; отличает ротацию от отзыва при выходе или краже. */
    val rotatedAt = timestamp("rotated_at").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
