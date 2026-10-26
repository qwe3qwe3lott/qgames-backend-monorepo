package site.qgames.game.model.user

import java.util.UUID

/** Публичный профиль пользователя: читается из таблицы user модуля user-auth (UserProfileTable из user-auth-api). */
data class UserProfileModel(
    val id: UUID,
    val nickname: String,
    val avatar: UserAvatar,
)

enum class UserAvatar {
    CAT,
    DOG,
}
