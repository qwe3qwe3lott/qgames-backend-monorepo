package site.qgames.game.mapper.room

import org.springframework.stereotype.Component
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomSettingsChangedDto
import site.qgames.game.gatewayapi.dto.room.RoomUsersDto
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.room.RoomUsersModel

/**
 * Данные событий потока комнаты (поле `data:` SSE) → DTO контракта game-user-gateway-sync-api (RoomStreamEvent).
 * Только преобразование, без обращений к данным.
 */
@Component
class RoomEventResponseMapper(
    private val roomResponseMapper: RoomResponseMapper,
    private val roomSettingsResponseMapper: RoomSettingsResponseMapper,
) {

    /** snapshot: комната целиком, как в GET комнаты. */
    fun toSnapshot(details: RoomDetailsModel): RoomResponse = roomResponseMapper.toResponse(details)

    /** users-changed. */
    fun toUsersChanged(users: RoomUsersModel) = RoomUsersDto(
        ownerId = users.room.ownerId,
        members = users.members.map { roomResponseMapper.toResponse(it, users.room.ownerId) },
    )

    /** settings-changed. */
    fun toSettingsChanged(settings: RoomSettingsModel) = RoomSettingsChangedDto(roomSettingsResponseMapper.toResponse(settings))

    /** session-changed и room-deleted: данных нет, в потоке — `{}`. */
    fun empty(): Map<String, Any?> = emptyMap()
}
