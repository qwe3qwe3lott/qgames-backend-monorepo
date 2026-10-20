package site.qgames.usergateway.mapper.game

import site.qgames.usergateway.mapper.room.toPublic
import site.qgames.usergateway.publicapi.dto.game.GameCode
import site.qgames.usergateway.publicapi.dto.game.GameDetailsResponse
import site.qgames.usergateway.publicapi.dto.game.GameListResponse
import site.qgames.usergateway.publicapi.dto.game.GamePublishStatus
import site.qgames.usergateway.publicapi.dto.game.GameRulesDto
import site.qgames.usergateway.publicapi.dto.game.GameSummaryDto
import site.qgames.usergateway.publicapi.dto.game.TimeControlLimitsDto
import site.qgames.game.gatewayapi.dto.game.GameCode as GameGameCode
import site.qgames.game.gatewayapi.dto.game.GameDetailsResponse as GameGameDetailsResponse
import site.qgames.game.gatewayapi.dto.game.GameListResponse as GameGameListResponse
import site.qgames.game.gatewayapi.dto.game.GamePublishStatus as GameGamePublishStatus
import site.qgames.game.gatewayapi.dto.game.GameRulesDto as GameGameRulesDto
import site.qgames.game.gatewayapi.dto.game.GameSummaryDto as GameGameSummaryDto
import site.qgames.game.gatewayapi.dto.game.TimeControlLimitsDto as GameTimeControlLimitsDto

/**
 * Каталог игр: контракт сервиса game → публичный контракт gateway.
 */
fun GameGameListResponse.toPublic() = GameListResponse(games = games.map { it.toPublic() })

fun GameGameDetailsResponse.toPublic() = GameDetailsResponse(
    code = code.toPublic(),
    title = title,
    description = description,
    publishStatus = publishStatus.toPublic(),
    rules = rules.toPublic(),
)

fun GameCode.toGame(): GameGameCode = when (this) {
    GameCode.NOTHING -> GameGameCode.NOTHING
    GameCode.CARCASSONNE -> GameGameCode.CARCASSONNE
}

fun GameGameCode.toPublic(): GameCode = when (this) {
    GameGameCode.NOTHING -> GameCode.NOTHING
    GameGameCode.CARCASSONNE -> GameCode.CARCASSONNE
}

private fun GameGameSummaryDto.toPublic() = GameSummaryDto(
    code = code.toPublic(),
    title = title,
    description = description,
    publishStatus = publishStatus.toPublic(),
)

private fun GameGameRulesDto.toPublic() = GameRulesDto(
    minPlayers = minPlayers,
    maxPlayers = maxPlayers,
    timeControl = timeControl.toPublic(),
    gameSettingsLimits = gameSettingsLimits.toPublic(),
    defaultSettings = defaultSettings.toPublic(),
)

private fun GameGamePublishStatus.toPublic(): GamePublishStatus = when (this) {
    GameGamePublishStatus.PUBLISHED -> GamePublishStatus.PUBLISHED
    GameGamePublishStatus.DISABLED -> GamePublishStatus.DISABLED
    GameGamePublishStatus.HIDDEN -> GamePublishStatus.HIDDEN
}

private fun GameTimeControlLimitsDto.toPublic() = TimeControlLimitsDto(
    modes = modes.map { it.toPublic() },
    moveSeconds = moveSeconds.toPublic(),
)
