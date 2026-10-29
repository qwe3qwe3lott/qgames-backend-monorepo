package site.qgames.game.mapper.game

import org.springframework.stereotype.Component
import site.qgames.game.api.dto.game.GameDetailsResponse
import site.qgames.game.api.dto.game.GameListResponse
import site.qgames.game.api.dto.game.GameRulesDto
import site.qgames.game.api.dto.game.GameSummaryDto
import site.qgames.game.api.dto.game.TimeControlLimitsDto
import site.qgames.game.mapper.room.RoomSettingsResponseMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GameDetailsModel
import site.qgames.game.model.game.GameModel
import site.qgames.game.model.game.GameRulesModel
import site.qgames.game.api.dto.game.GameCode as GameCodeDto

/** Каталог игр → DTO контракта. */
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
        spectators = roomSettingsResponseMapper.toDto(rules.spectators),
        timeControl = TimeControlLimitsDto(
            optional = rules.timeControlOptional,
            baseSeconds = roomSettingsResponseMapper.toDto(rules.baseSeconds),
            bonusSeconds = roomSettingsResponseMapper.toDto(rules.bonusSeconds),
        ),
        gameSettingsLimits = roomSettingsResponseMapper.toLimitsResponse(rules.settingsLimits),
        defaultSettings = roomSettingsResponseMapper.toResponse(rules.defaultSettings),
    )
}
