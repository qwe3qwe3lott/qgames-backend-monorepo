package site.qgames.game.mapper.game

import org.springframework.stereotype.Component
import site.qgames.game.gatewayapi.dto.game.GameDetailsResponse
import site.qgames.game.gatewayapi.dto.game.GameListResponse
import site.qgames.game.gatewayapi.dto.game.GameRulesDto
import site.qgames.game.gatewayapi.dto.game.GameSummaryDto
import site.qgames.game.gatewayapi.dto.game.TimeControlLimitsDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.mapper.room.RoomSettingsResponseMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GameDetailsModel
import site.qgames.game.model.game.GameModel
import site.qgames.game.model.game.GameRulesModel
import site.qgames.game.gatewayapi.dto.game.GameCode as GameCodeDto

/** Каталог игр → DTO контракта для gateway. */
@Component
class GameResponseMapper(private val roomSettingsResponseMapper: RoomSettingsResponseMapper) {

    fun toListResponse(games: List<GameModel>) = GameListResponse(
        games = games.map {
            GameSummaryDto(
                code = toDto(it.code),
                title = it.title,
                description = it.description,
            )
        },
    )

    fun toResponse(details: GameDetailsModel) = GameDetailsResponse(
        code = toDto(details.game.code),
        title = details.game.title,
        description = details.game.description,
        rules = toDto(details.rules),
    )

    fun toDto(code: GameCode): GameCodeDto = GameCodeDto.valueOf(code.name)

    private fun toDto(rules: GameRulesModel) = GameRulesDto(
        minPlayers = rules.minPlayers,
        maxPlayers = rules.maxPlayers,
        timeControl = TimeControlLimitsDto(
            modes = rules.timeControlModes.map { TimeControlMode.valueOf(it.name) },
            moveSeconds = roomSettingsResponseMapper.toDto(rules.moveSeconds),
        ),
        gameSettingsLimits = roomSettingsResponseMapper.toLimitsResponse(rules.settingsLimits),
        defaultSettings = roomSettingsResponseMapper.toResponse(rules.defaultSettings),
    )
}
