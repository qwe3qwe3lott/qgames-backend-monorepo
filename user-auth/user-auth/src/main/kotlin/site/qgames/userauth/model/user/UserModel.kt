package site.qgames.userauth.model.user

import java.time.Instant
import java.util.UUID

data class UserModel(
    val id: UUID,
    val email: String,
    /** Не уникален, игроки различаются по id. */
    val nickname: String,
    val avatar: UserAvatar,
    val createdAt: Instant,
)

enum class UserAvatar {
    CAT,
    DOG,
}
