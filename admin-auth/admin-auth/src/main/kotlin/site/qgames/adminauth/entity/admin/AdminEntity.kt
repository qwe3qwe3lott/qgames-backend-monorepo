package site.qgames.adminauth.entity.admin

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/** Строка таблицы admin. */
@Table("admin_auth#admin")
data class AdminEntity(
    @Id
    val id: UUID? = null,
    val login: String,
    val passwordHash: String,
    val createdAt: Instant,
)
