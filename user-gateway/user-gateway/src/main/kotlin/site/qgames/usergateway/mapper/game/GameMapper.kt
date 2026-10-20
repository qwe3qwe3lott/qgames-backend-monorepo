package site.qgames.usergateway.mapper.game

import site.qgames.usergateway.publicapi.dto.game.GameDetailsResponse
import site.qgames.usergateway.publicapi.dto.game.GameListResponse
import site.qgames.usergateway.publicapi.dto.game.GamePublishStatus
import site.qgames.usergateway.publicapi.dto.game.GameRulesDto
import site.qgames.usergateway.publicapi.dto.game.GameSummaryDto
import site.qgames.usergateway.publicapi.dto.game.TimeControlLimitsDto
import site.qgames.game.gatewayapi.dto.game.GameDetailsResponse as GameGameDetailsResponse
import site.qgames.game.gatewayapi.dto.game.GameListResponse as GameGameListResponse
import site.qgames.game.gatewayapi.dto.game.GamePublishStatus as GameGamePublishStatus
import site.qgames.game.gatewayapi.dto.game.GameRulesDto as GameGameRulesDto
import site.qgames.game.gatewayapi.dto.game.GameSummaryDto as GameGameSummaryDto
import site.qgames.game.gatewayapi.dto.game.TimeControlLimitsDto as GameTimeControlLimitsDto

/**
 * Каталог игр: контракт сервиса game → публичный контракт gateway. Общие с комнатами типы (код игры, настройки и их
 * границы) в публичном контракте — те же DTO game, они передаются как есть.
 */
fun GameGameListResponse.toPublic() = GameListResponse(games = games.map { it.toPublic() })

fun GameGameDetailsResponse.toPublic() = GameDetailsResponse(
    code = code,
    title = title,
    description = description,
    publishStatus = publishStatus.toPublic(),
    rules = rules.toPublic(),
)

private fun GameGameSummaryDto.toPublic() = GameSummaryDto(
    code = code,
    title = title,
    description = description,
    publishStatus = publishStatus.toPublic(),
)

private fun GameGameRulesDto.toPublic() = GameRulesDto(
    minPlayers = minPlayers,
    maxPlayers = maxPlayers,
    timeControl = timeControl.toPublic(),
    gameSettingsLimits = gameSettingsLimits,
    defaultSettings = defaultSettings,
)

private fun GameGamePublishStatus.toPublic(): GamePublishStatus = when (this) {
    GameGamePublishStatus.PUBLISHED -> GamePublishStatus.PUBLISHED
    GameGamePublishStatus.DISABLED -> GamePublishStatus.DISABLED
    GameGamePublishStatus.HIDDEN -> GamePublishStatus.HIDDEN
}

private fun GameTimeControlLimitsDto.toPublic() = TimeControlLimitsDto(
    modes = modes,
    moveSeconds = moveSeconds,
)
