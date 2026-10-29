package site.qgames.game.model.room

import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.user.UserProfileModel

/** Комната целиком: результат операций RoomService и RoomSessionService. */
data class RoomDetailsModel(
    val room: RoomModel,
    val minPlayers: Int,
    val maxPlayers: Int,
    /** Настройки комнаты; настройки игры уже разобраны в класс модуля игры. */
    val settings: RoomSettingsModel,
    /** В порядке входа. */
    val members: List<RoomMemberDetailsModel>,
    /** Текущая партия, а если её нет — последняя. */
    val session: RoomSessionModel?,
)

data class RoomMemberDetailsModel(
    val member: RoomMemberModel,
    /** Профиль из таблицы user модуля user-auth; null — строки пользователя нет. */
    val profile: UserProfileModel?,
)
