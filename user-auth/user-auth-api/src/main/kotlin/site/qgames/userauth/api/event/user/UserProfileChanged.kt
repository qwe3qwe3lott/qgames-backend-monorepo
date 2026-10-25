package site.qgames.userauth.api.event.user

import site.qgames.userauth.api.dto.user.Avatar
import java.util.UUID

/**
 * Публичный профиль пользователя изменился (ник или аватар). user-auth публикует событие в транзакции изменения,
 * слушатели получают его после коммита (`@TransactionalEventListener(AFTER_COMMIT)`). Сам профиль другие модули читают
 * из таблицы `user` через [site.qgames.userauth.api.table.user.UserProfileTable]; событие нужно, чтобы отреагировать
 * на изменение (например, разослать новый ник в комнаты).
 */
data class UserProfileChanged(
    val id: UUID,
    val nickname: String,
    val avatar: Avatar,
)
