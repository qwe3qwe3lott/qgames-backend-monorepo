package site.qgames.usergateway.mapper.game

import site.qgames.usergateway.mapper.room.toPublic
import site.qgames.usergateway.publicapi.dto.game.GameInfoResponse
import site.qgames.usergateway.publicapi.dto.game.TimeControlLimitsDto
import site.qgames.game.gatewayapi.dto.game.GameInfoResponse as GameGameInfoResponse
import site.qgames.game.gatewayapi.dto.game.TimeControlLimitsDto as GameTimeControlLimitsDto

/**
 * Параметры игры: контракт сервиса game → публичный контракт gateway.
 */
fun GameGameInfoResponse.toPublic() = GameInfoResponse(
    gameType = gameType.toPublic(),
    minPlayers = minPlayers,
    maxPlayers = maxPlayers,
    timeControl = timeControl.toPublic(),
    gameSettingsLimits = gameSettingsLimits.toPublic(),
    defaultSettings = defaultSettings.toPublic(),
)

private fun GameTimeControlLimitsDto.toPublic() = TimeControlLimitsDto(
    modes = modes.map { it.toPublic() },
    moveSeconds = moveSeconds.toPublic(),
)
