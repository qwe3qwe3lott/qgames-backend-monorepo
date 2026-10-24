package site.qgames.userauth.api.event.user

import site.qgames.userauth.api.dto.user.Avatar
import java.util.UUID

/**
 * Снапшот публичного профиля пользователя (колонки `"user_auth#user"`). user-auth публикует его в транзакции создания
 * аккаунта и каждой смены профиля; доставляет Spring Modulith после коммита (реестр публикаций в БД, незавершённые
 * доставки переотправляются при старте). Потребитель применяет событие, только если [version] больше уже сохранённой:
 * повторы и перестановки безопасны.
 */
data class UserProfileChanged(
    val id: UUID,
    val nickname: String,
    val avatar: Avatar,
    /** Версия строки `"user_auth#user"` (растёт при каждом изменении). */
    val version: Long,
)
