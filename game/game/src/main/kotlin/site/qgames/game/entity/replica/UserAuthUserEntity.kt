package site.qgames.game.entity.replica

import java.util.UUID

/**
 * Строка реплики `"user_auth#user"`: только нужные game колонки оригинала user_auth."user", email не реплицируется.
 */
data class UserAuthUserEntity(
    val id: UUID,
    val nickname: String,
    val avatar: String,
    val version: Long,
)
