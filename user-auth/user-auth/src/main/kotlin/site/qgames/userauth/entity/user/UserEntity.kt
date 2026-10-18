package site.qgames.userauth.entity.user

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("user")
data class UserEntity(
    @Id
    val id: UUID? = null,
    val email: String,
    val emailVerified: Boolean,
    val createdAt: Instant,
)
