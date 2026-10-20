package site.qgames.game.mapper.room

import org.springframework.stereotype.Component
import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.room.Avatar
import site.qgames.game.gatewayapi.dto.room.RoomMemberResponse
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.gatewayapi.dto.room.RoomStatus
import site.qgames.game.gatewayapi.dto.room.SessionResponse
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomMemberDetailsModel
import site.qgames.game.model.room.RoomSessionDetailsModel
import site.qgames.game.model.session.RoomSessionStatus
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Модель комнаты → DTO контракта для gateway. Только преобразование, без обращений к данным. */
@Component
class RoomResponseMapper(
    private val jsonMapper: JsonMapper,
    private val roomSettingsResponseMapper: RoomSettingsResponseMapper,
) {

    fun toResponse(details: RoomDetailsModel): RoomResponse {
        val room = details.room
        return RoomResponse(
            id = room.id,
            gameCode = GameCode.valueOf(room.gameCode.name),
            status = RoomStatus.valueOf(room.status.name),
            ownerId = room.ownerId,
            minPlayers = details.minPlayers,
            maxPlayers = details.maxPlayers,
            members = details.members.map { toResponse(it, room.ownerId) },
            session = details.session?.let(::toResponse),
            createdAt = room.createdAt,
            settings = roomSettingsResponseMapper.toResponse(details.settings),
            serverTime = details.serverTime,
        )
    }

    /** Участник комнаты с владельцем [ownerId]. */
    fun toResponse(details: RoomMemberDetailsModel, ownerId: UUID?): RoomMemberResponse {
        val (member, profile) = details
        return RoomMemberResponse(
            userId = member.userId,
            nickname = profile?.nickname,
            avatar = profile?.avatar?.let { Avatar.valueOf(it.name) },
            role = RoomRole.valueOf(member.role.name),
            owner = member.userId == ownerId,
            leaving = member.leaveDeadline != null,
            joinedAt = member.joinedAt,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun toResponse(details: RoomSessionDetailsModel) = SessionResponse(
        id = details.session.id,
        status = SessionStatus.valueOf(details.session.status.name),
        players = details.players,
        version = details.session.version,
        state = jsonMapper.convertValue(details.view, Map::class.java) as Map<String, Any?>,
        moveDeadline = details.session.moveDeadline.takeIf { details.session.status == RoomSessionStatus.IN_PROGRESS },
        pausedAt = details.session.pausedAt.takeIf { details.session.status == RoomSessionStatus.IN_PROGRESS },
    )
}
