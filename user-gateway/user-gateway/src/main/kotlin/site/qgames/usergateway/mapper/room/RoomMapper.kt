package site.qgames.usergateway.mapper.room

import site.qgames.usergateway.mapper.game.toGame
import site.qgames.usergateway.mapper.game.toPublic
import site.qgames.usergateway.publicapi.dto.room.ChangeRoleRequest
import site.qgames.usergateway.publicapi.dto.room.CreateRoomRequest
import site.qgames.usergateway.publicapi.dto.room.GameSettingsDto
import site.qgames.usergateway.publicapi.dto.room.GameSettingsLimitsDto
import site.qgames.usergateway.publicapi.dto.room.IntRangeDto
import site.qgames.usergateway.publicapi.dto.room.MoveRequest
import site.qgames.usergateway.publicapi.dto.room.NothingSettingsDto
import site.qgames.usergateway.publicapi.dto.room.NothingSettingsLimitsDto
import site.qgames.usergateway.publicapi.dto.room.RoomMemberResponse
import site.qgames.usergateway.publicapi.dto.room.RoomResponse
import site.qgames.usergateway.publicapi.dto.room.RoomRole
import site.qgames.usergateway.publicapi.dto.room.RoomSettingsDto
import site.qgames.usergateway.publicapi.dto.room.RoomStatus
import site.qgames.usergateway.publicapi.dto.room.SessionResponse
import site.qgames.usergateway.publicapi.dto.room.SessionStatus
import site.qgames.usergateway.publicapi.dto.room.TimeControlDto
import site.qgames.usergateway.publicapi.dto.room.TimeControlMode
import site.qgames.usergateway.publicapi.dto.user.Avatar
import site.qgames.game.gatewayapi.dto.room.Avatar as GameAvatar
import site.qgames.game.gatewayapi.dto.room.ChangeRoleRequest as GameChangeRoleRequest
import site.qgames.game.gatewayapi.dto.room.CreateRoomRequest as GameCreateRoomRequest
import site.qgames.game.gatewayapi.dto.room.GameSettingsDto as GameGameSettingsDto
import site.qgames.game.gatewayapi.dto.room.GameSettingsLimitsDto as GameGameSettingsLimitsDto
import site.qgames.game.gatewayapi.dto.room.IntRangeDto as GameIntRangeDto
import site.qgames.game.gatewayapi.dto.room.MoveRequest as GameMoveRequest
import site.qgames.game.gatewayapi.dto.room.NothingSettingsDto as GameNothingSettingsDto
import site.qgames.game.gatewayapi.dto.room.NothingSettingsLimitsDto as GameNothingSettingsLimitsDto
import site.qgames.game.gatewayapi.dto.room.RoomMemberResponse as GameRoomMemberResponse
import site.qgames.game.gatewayapi.dto.room.RoomResponse as GameRoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole as GameRoomRole
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto as GameRoomSettingsDto
import site.qgames.game.gatewayapi.dto.room.RoomStatus as GameRoomStatus
import site.qgames.game.gatewayapi.dto.room.SessionResponse as GameSessionResponse
import site.qgames.game.gatewayapi.dto.room.SessionStatus as GameSessionStatus
import site.qgames.game.gatewayapi.dto.room.TimeControlDto as GameTimeControlDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode as GameTimeControlMode

/**
 * Маппинг между публичным контрактом gateway и контрактом сервиса game. Внутренние DTO наружу не отдаются.
 */
fun CreateRoomRequest.toGame() = GameCreateRoomRequest(settings.toGame())

fun ChangeRoleRequest.toGame() = GameChangeRoleRequest(role.toGame())

fun MoveRequest.toGame() = GameMoveRequest(type = type, data = data, expectedVersion = expectedVersion)

fun RoomSettingsDto.toGame() = GameRoomSettingsDto(timeControl = timeControl.toGame(), gameSettings = gameSettings.toGame())

fun GameRoomResponse.toPublic() = RoomResponse(
    id = id,
    gameCode = gameCode.toPublic(),
    status = status.toPublic(),
    ownerId = ownerId,
    minPlayers = minPlayers,
    maxPlayers = maxPlayers,
    members = members.map { it.toPublic() },
    session = session?.toPublic(),
    createdAt = createdAt,
    settings = settings.toPublic(),
    serverTime = serverTime,
)

fun GameRoomSettingsDto.toPublic() = RoomSettingsDto(timeControl = timeControl.toPublic(), gameSettings = gameSettings.toPublic())

fun GameGameSettingsLimitsDto.toPublic(): GameSettingsLimitsDto = when (this) {
    is GameNothingSettingsLimitsDto -> NothingSettingsLimitsDto(gameCode = gameCode.toPublic(), rounds = rounds.toPublic())
}

fun GameIntRangeDto.toPublic() = IntRangeDto(min = min, max = max)

fun GameTimeControlMode.toPublic(): TimeControlMode = when (this) {
    GameTimeControlMode.NONE -> TimeControlMode.NONE
    GameTimeControlMode.PER_MOVE -> TimeControlMode.PER_MOVE
}

private fun TimeControlMode.toGame(): GameTimeControlMode = when (this) {
    TimeControlMode.NONE -> GameTimeControlMode.NONE
    TimeControlMode.PER_MOVE -> GameTimeControlMode.PER_MOVE
}

private fun TimeControlDto.toGame() = GameTimeControlDto(mode = mode.toGame(), moveSeconds = moveSeconds)

private fun GameTimeControlDto.toPublic() = TimeControlDto(mode = mode.toPublic(), moveSeconds = moveSeconds)

private fun GameSettingsDto.toGame(): GameGameSettingsDto = when (this) {
    is NothingSettingsDto -> GameNothingSettingsDto(gameCode = gameCode.toGame(), rounds = rounds)
}

private fun GameGameSettingsDto.toPublic(): GameSettingsDto = when (this) {
    is GameNothingSettingsDto -> NothingSettingsDto(gameCode = gameCode.toPublic(), rounds = rounds)
}

private fun GameRoomMemberResponse.toPublic() = RoomMemberResponse(
    userId = userId,
    nickname = nickname,
    avatar = avatar?.toPublic(),
    role = role.toPublic(),
    owner = owner,
    leaving = leaving,
    joinedAt = joinedAt,
)

private fun GameSessionResponse.toPublic() = SessionResponse(
    id = id,
    status = status.toPublic(),
    players = players,
    version = version,
    state = state,
    moveDeadline = moveDeadline,
    pausedAt = pausedAt,
)

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
