package site.qgames.userauth.entity.user

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * Способ входа пользователя. Для PASSWORD subject — email, для GOOGLE — claim `sub` из Google ID token.
 */
@Table("user_identity")
data class UserIdentityEntity(
    @Id
    val id: UUID? = null,
    val userId: UUID,
    val provider: IdentityProvider,
    val subject: String,
    val passwordHash: String? = null,
    val createdAt: Instant,
)
