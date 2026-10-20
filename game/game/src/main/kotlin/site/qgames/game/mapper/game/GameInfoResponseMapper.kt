package site.qgames.game.mapper.game

import org.springframework.stereotype.Component
import site.qgames.game.gatewayapi.dto.game.GameInfoResponse
import site.qgames.game.gatewayapi.dto.game.TimeControlLimitsDto
import site.qgames.game.gatewayapi.dto.room.GameType
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.mapper.room.RoomSettingsResponseMapper
import site.qgames.game.model.game.GameInfoModel

/** Параметры игры → DTO контракта для gateway. */
@Component
class GameInfoResponseMapper(private val roomSettingsResponseMapper: RoomSettingsResponseMapper) {

    fun toResponse(model: GameInfoModel) = GameInfoResponse(
        gameType = GameType.valueOf(model.gameType.name),
        minPlayers = model.minPlayers,
        maxPlayers = model.maxPlayers,
        timeControl = TimeControlLimitsDto(
            modes = model.timeControlModes.map { TimeControlMode.valueOf(it.name) },
            moveSeconds = roomSettingsResponseMapper.toDto(model.moveSeconds),
        ),
        gameSettingsLimits = roomSettingsResponseMapper.toLimitsResponse(model.settingsLimits),
        defaultSettings = roomSettingsResponseMapper.toResponse(model.defaultSettings),
    )
}
