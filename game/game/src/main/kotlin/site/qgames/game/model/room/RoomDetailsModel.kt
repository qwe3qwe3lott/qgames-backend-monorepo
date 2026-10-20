package site.qgames.game.model.room

import site.qgames.game.model.replica.UserAuthUserModel
import site.qgames.game.model.session.RoomSessionModel
import java.time.Instant
import java.util.UUID

/** Комната целиком, как её видит конкретный участник: результат операций RoomService и RoomSessionService. */
data class RoomDetailsModel(
    val room: RoomModel,
    val minPlayers: Int,
    val maxPlayers: Int,
    /** Настройки комнаты; настройки игры уже разобраны в класс модуля игры. */
    val settings: RoomSettingsModel,
    /** В порядке входа. */
    val members: List<RoomMemberDetailsModel>,
    /** Текущая партия, а если её нет — последняя. */
    val session: RoomSessionDetailsModel?,
    /** Время сервера на момент сборки ответа: по нему клиент поправляет часы для обратного отсчёта. */
    val serverTime: Instant,
)

data class RoomMemberDetailsModel(
    val member: RoomMemberModel,
    /** Нет, если профиль ещё не доставлен по CDC. */
    val profile: UserAuthUserModel?,
)

data class RoomSessionDetailsModel(
    val session: RoomSessionModel,
    /** Игроки в порядке мест. */
    val players: List<UUID>,
    /** Состояние партии глазами участника (GameRules.view), формат задаёт модуль игры. */
    val view: Any,
)
