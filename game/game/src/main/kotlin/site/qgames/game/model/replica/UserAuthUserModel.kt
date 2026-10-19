package site.qgames.game.model.replica

import java.util.UUID

/** Публичный профиль пользователя из реплики user_auth."user". */
data class UserAuthUserModel(
    val id: UUID,
    val nickname: String,
    val avatar: UserAvatar,
    /** Версия строки в источнике: применяется только более новый снапшот. */
    val version: Long,
)

enum class UserAvatar {
    CAT,
    DOG,
}
