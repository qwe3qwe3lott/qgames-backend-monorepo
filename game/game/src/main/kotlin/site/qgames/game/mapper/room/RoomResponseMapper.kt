package site.qgames.game.mapper.room

import org.springframework.stereotype.Component
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.room.Avatar
import site.qgames.game.api.dto.room.RoomMemberResponse
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.RoomRole
import site.qgames.game.api.dto.room.RoomStatus
import site.qgames.game.api.dto.room.SessionResponse
import site.qgames.game.api.dto.room.SessionStatus
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomMemberDetailsModel
import site.qgames.game.model.session.RoomSessionModel
import java.util.UUID

/** Модель комнаты → DTO контракта. Только преобразование, без обращений к данным. */
@Component
class RoomResponseMapper(
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
            leaving = member.leftAt != null,
            joinedAt = member.joinedAt,
        )
    }

    private fun toResponse(session: RoomSessionModel) = SessionResponse(
        id = session.id,
        status = SessionStatus.valueOf(session.status.name),
    )
}
