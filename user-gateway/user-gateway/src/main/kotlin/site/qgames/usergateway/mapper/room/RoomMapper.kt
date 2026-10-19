package site.qgames.usergateway.mapper.room

import site.qgames.usergateway.publicapi.dto.room.ChangeRoleRequest
import site.qgames.usergateway.publicapi.dto.room.CreateRoomRequest
import site.qgames.usergateway.publicapi.dto.room.GameType
import site.qgames.usergateway.publicapi.dto.room.MoveRequest
import site.qgames.usergateway.publicapi.dto.room.RoomMemberResponse
import site.qgames.usergateway.publicapi.dto.room.RoomResponse
import site.qgames.usergateway.publicapi.dto.room.RoomRole
import site.qgames.usergateway.publicapi.dto.room.RoomStatus
import site.qgames.usergateway.publicapi.dto.room.SessionResponse
import site.qgames.usergateway.publicapi.dto.room.SessionStatus
import site.qgames.usergateway.publicapi.dto.user.Avatar
import site.qgames.game.gatewayapi.dto.room.Avatar as GameAvatar
import site.qgames.game.gatewayapi.dto.room.ChangeRoleRequest as GameChangeRoleRequest
import site.qgames.game.gatewayapi.dto.room.CreateRoomRequest as GameCreateRoomRequest
import site.qgames.game.gatewayapi.dto.room.GameType as GameGameType
import site.qgames.game.gatewayapi.dto.room.MoveRequest as GameMoveRequest
import site.qgames.game.gatewayapi.dto.room.RoomMemberResponse as GameRoomMemberResponse
import site.qgames.game.gatewayapi.dto.room.RoomResponse as GameRoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole as GameRoomRole
import site.qgames.game.gatewayapi.dto.room.RoomStatus as GameRoomStatus
import site.qgames.game.gatewayapi.dto.room.SessionResponse as GameSessionResponse
import site.qgames.game.gatewayapi.dto.room.SessionStatus as GameSessionStatus

/**
 * Маппинг между публичным контрактом gateway и контрактом сервиса game. Внутренние DTO наружу не отдаются.
 */
fun CreateRoomRequest.toGame() = GameCreateRoomRequest(gameType.toGame())

fun ChangeRoleRequest.toGame() = GameChangeRoleRequest(role.toGame())

fun MoveRequest.toGame() = GameMoveRequest(type = type, data = data, expectedVersion = expectedVersion)

fun GameRoomResponse.toPublic() = RoomResponse(
    id = id,
    gameType = gameType.toPublic(),
    status = status.toPublic(),
    ownerId = ownerId,
    minPlayers = minPlayers,
    maxPlayers = maxPlayers,
    members = members.map { it.toPublic() },
    session = session?.toPublic(),
    createdAt = createdAt,
)

private fun GameRoomMemberResponse.toPublic() = RoomMemberResponse(
    userId = userId,
    nickname = nickname,
    avatar = avatar?.toPublic(),
    role = role.toPublic(),
    owner = owner,
    joinedAt = joinedAt,
)

private fun GameSessionResponse.toPublic() = SessionResponse(
    id = id,
    status = status.toPublic(),
    players = players,
    version = version,
    state = state,
)

fun GameType.toGame(): GameGameType = when (this) {
    GameType.NOTHING -> GameGameType.NOTHING
}

private fun GameGameType.toPublic(): GameType = when (this) {
    GameGameType.NOTHING -> GameType.NOTHING
}

private fun RoomRole.toGame(): GameRoomRole = when (this) {
    RoomRole.PLAYER -> GameRoomRole.PLAYER
    RoomRole.SPECTATOR -> GameRoomRole.SPECTATOR
}

private fun GameRoomRole.toPublic(): RoomRole = when (this) {
    GameRoomRole.PLAYER -> RoomRole.PLAYER
    GameRoomRole.SPECTATOR -> RoomRole.SPECTATOR
}

private fun GameRoomStatus.toPublic(): RoomStatus = when (this) {
    GameRoomStatus.WAITING -> RoomStatus.WAITING
    GameRoomStatus.IN_GAME -> RoomStatus.IN_GAME
}

private fun GameSessionStatus.toPublic(): SessionStatus = when (this) {
    GameSessionStatus.IN_PROGRESS -> SessionStatus.IN_PROGRESS
    GameSessionStatus.FINISHED -> SessionStatus.FINISHED
    GameSessionStatus.CANCELLED -> SessionStatus.CANCELLED
}

private fun GameAvatar.toPublic(): Avatar = when (this) {
    GameAvatar.CAT -> Avatar.CAT
    GameAvatar.DOG -> Avatar.DOG
}
