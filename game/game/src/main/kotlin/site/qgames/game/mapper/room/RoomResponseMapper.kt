package site.qgames.game.mapper.room

import org.springframework.stereotype.Component
import site.qgames.game.gatewayapi.dto.room.Avatar
import site.qgames.game.gatewayapi.dto.room.GameType
import site.qgames.game.gatewayapi.dto.room.RoomMemberResponse
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.gatewayapi.dto.room.RoomStatus
import site.qgames.game.gatewayapi.dto.room.SessionResponse
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomSessionDetailsModel
import tools.jackson.databind.json.JsonMapper

/** Модель комнаты → DTO контракта для gateway. Только преобразование, без обращений к данным. */
@Component
class RoomResponseMapper(private val jsonMapper: JsonMapper) {

    fun toResponse(details: RoomDetailsModel): RoomResponse {
        val room = details.room
        return RoomResponse(
            id = room.id,
            gameType = GameType.valueOf(room.gameType.name),
            status = RoomStatus.valueOf(room.status.name),
            ownerId = room.ownerId,
            minPlayers = details.minPlayers,
            maxPlayers = details.maxPlayers,
            members = details.members.map { (member, profile) ->
                RoomMemberResponse(
                    userId = member.userId,
                    nickname = profile?.nickname,
                    avatar = profile?.avatar?.let { Avatar.valueOf(it.name) },
                    role = RoomRole.valueOf(member.role.name),
                    owner = member.userId == room.ownerId,
                    joinedAt = member.joinedAt,
                )
            },
            session = details.session?.let(::toResponse),
            createdAt = room.createdAt,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun toResponse(details: RoomSessionDetailsModel) = SessionResponse(
        id = details.session.id,
        status = SessionStatus.valueOf(details.session.status.name),
        players = details.players,
        version = details.session.version,
        state = jsonMapper.convertValue(details.view, Map::class.java) as Map<String, Any?>,
    )
}
