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
    /** Версия строки: оптимистичная блокировка и порядок CDC-событий. */
    val version: Long,
)

enum class UserAvatar {
    CAT,
    DOG,
}
