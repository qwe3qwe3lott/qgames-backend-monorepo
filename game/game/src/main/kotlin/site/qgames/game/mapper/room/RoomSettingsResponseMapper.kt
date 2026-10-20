package site.qgames.game.mapper.room

import org.springframework.stereotype.Component
import site.qgames.game.gatewayapi.dto.room.GameSettingsDto
import site.qgames.game.gatewayapi.dto.room.GameSettingsLimitsDto
import site.qgames.game.gatewayapi.dto.room.GameType
import site.qgames.game.gatewayapi.dto.room.IntRangeDto
import site.qgames.game.gatewayapi.dto.room.NothingSettingsDto
import site.qgames.game.gatewayapi.dto.room.NothingSettingsLimitsDto
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.game.gatewayapi.dto.room.TimeControlDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.room.RoomTimeControlMode
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.games.nothing.NothingSettings
import site.qgames.games.nothing.NothingSettingsLimits

/**
 * Настройки комнаты: DTO контракта ↔ модель. Настройки и границы конкретной игры — классы её модуля, поэтому
 * новая игра добавляет сюда свою ветку (как и свой DTO в контракт).
 */
@Component
class RoomSettingsResponseMapper {

    fun toModel(dto: RoomSettingsDto) = RoomSettingsModel(
        timeControl = RoomTimeControlModel(RoomTimeControlMode.valueOf(dto.timeControl.mode.name), dto.timeControl.moveSeconds),
        gameSettings = when (val settings = dto.gameSettings) {
            is NothingSettingsDto -> NothingSettings(rounds = settings.rounds)
        },
    )

    fun toResponse(model: RoomSettingsModel) = RoomSettingsDto(
        timeControl = TimeControlDto(TimeControlMode.valueOf(model.timeControl.mode.name), model.timeControl.moveSeconds),
        gameSettings = toResponse(model.gameSettings),
    )

    fun toLimitsResponse(limits: Any): GameSettingsLimitsDto = when (limits) {
        is NothingSettingsLimits -> NothingSettingsLimitsDto(gameType = GameType.NOTHING, rounds = toDto(limits.rounds))
        else -> error("No DTO for game settings limits ${limits::class.qualifiedName}")
    }

    fun toDto(range: IntRange) = IntRangeDto(min = range.first, max = range.last)

    private fun toResponse(settings: Any): GameSettingsDto = when (settings) {
        is NothingSettings -> NothingSettingsDto(gameType = GameType.NOTHING, rounds = settings.rounds)
        else -> error("No DTO for game settings ${settings::class.qualifiedName}")
    }
}
