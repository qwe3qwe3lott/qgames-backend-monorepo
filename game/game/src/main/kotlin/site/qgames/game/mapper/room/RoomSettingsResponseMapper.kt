package site.qgames.game.mapper.room

import org.springframework.stereotype.Component
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.room.CheckersSettingsDto
import site.qgames.game.api.dto.room.CheckersSettingsLimitsDto
import site.qgames.game.api.dto.room.GameSettingsDto
import site.qgames.games.checkers.CheckersSettings
import site.qgames.games.checkers.CheckersSettingsLimits
import site.qgames.games.checkers.CheckersVariant
import site.qgames.game.api.dto.room.CheckersVariant as CheckersVariantDto
import site.qgames.game.api.dto.room.GameSettingsLimitsDto
import site.qgames.game.api.dto.room.IntRangeDto
import site.qgames.game.api.dto.room.NothingSettingsDto
import site.qgames.game.api.dto.room.NothingSettingsLimitsDto
import site.qgames.game.api.dto.room.RoomSettingsDto
import site.qgames.game.api.dto.room.TimeControlDto
import site.qgames.game.api.dto.room.CheckersTimePreset
import site.qgames.game.api.dto.room.CheckersTimePresetDto
import site.qgames.game.model.room.RoomSettingsModel
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
        timeControl = dto.timeControl?.let { RoomTimeControlModel(it.baseSeconds, it.incrementSeconds) },
        gameSettings = when (val settings = dto.gameSettings) {
            is NothingSettingsDto -> NothingSettings(rounds = settings.rounds)
            is CheckersSettingsDto -> CheckersSettings(CheckersVariant.valueOf(settings.variant.name))
        },
    )

    fun toResponse(model: RoomSettingsModel) = RoomSettingsDto(
        timeControl = model.timeControl?.let { TimeControlDto(it.baseSeconds, it.incrementSeconds) },
        gameSettings = toResponse(model.gameSettings),
    )

    fun toLimitsResponse(limits: Any): GameSettingsLimitsDto = when (limits) {
        is NothingSettingsLimits -> NothingSettingsLimitsDto(gameCode = GameCode.NOTHING, rounds = toDto(limits.rounds))
        is CheckersSettingsLimits -> CheckersSettingsLimitsDto(
            gameCode = GameCode.CHECKERS,
            variants = limits.variants.map { CheckersVariantDto.valueOf(it.name) },
            timePresets = limits.timePresets.map {
                CheckersTimePresetDto(
                    variant = CheckersVariantDto.valueOf(it.variant.name),
                    preset = CheckersTimePreset.valueOf(it.kind.name),
                    baseSeconds = it.baseSeconds,
                    incrementSeconds = it.incrementSeconds,
                )
            },
        )
        else -> error("No DTO for game settings limits ${limits::class.qualifiedName}")
    }

    fun toDto(range: IntRange) = IntRangeDto(min = range.first, max = range.last)

    private fun toResponse(settings: Any): GameSettingsDto = when (settings) {
        is NothingSettings -> NothingSettingsDto(gameCode = GameCode.NOTHING, rounds = settings.rounds)
        is CheckersSettings -> CheckersSettingsDto(gameCode = GameCode.CHECKERS, variant = CheckersVariantDto.valueOf(settings.variant.name))
        else -> error("No DTO for game settings ${settings::class.qualifiedName}")
    }
}
