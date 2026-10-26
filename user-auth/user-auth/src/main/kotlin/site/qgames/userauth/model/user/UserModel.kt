package site.qgames.userauth.model.user

import java.time.Instant
import java.util.UUID

data class UserModel(
    val id: UUID,
    /** Уникален и не меняется. */
    val login: String,
    /** Не уникален, игроки различаются по id. */
    val nickname: String,
    val avatar: UserAvatar,
    val createdAt: Instant,
)

enum class UserAvatar {
    CAT,
    DOG,
}
