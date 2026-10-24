package site.qgames.userauth.entity.user

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/** Строка таблицы user_identity. */
@Table("user_auth#user_identity")
data class UserIdentityEntity(
    @Id
    val id: UUID? = null,
    val userId: UUID,
    val provider: String,
    val subject: String,
    val passwordHash: String? = null,
    val createdAt: Instant,
)
