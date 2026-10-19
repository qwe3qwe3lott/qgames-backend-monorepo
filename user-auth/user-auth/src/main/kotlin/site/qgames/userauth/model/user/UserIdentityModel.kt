package site.qgames.userauth.model.user

import java.time.Instant
import java.util.UUID

/**
 * Способ входа пользователя. Для PASSWORD subject — email, для GOOGLE — claim `sub` из Google ID token.
 */
data class UserIdentityModel(
    val id: UUID,
    val userId: UUID,
    val provider: IdentityProvider,
    val subject: String,
    val passwordHash: String?,
    val createdAt: Instant,
)

enum class IdentityProvider {
    PASSWORD,
    GOOGLE,
}
