package site.qgames.game.mapper.room

import org.springframework.stereotype.Component
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.room.CheckersAdvancedSettingsDto
import site.qgames.game.api.dto.room.CheckersSettingsLimitsDto
import site.qgames.game.api.dto.room.CheckersSimpleSettingsDto
import site.qgames.game.api.dto.room.CheckersTimePresetDto
import site.qgames.game.api.dto.room.GameAdvancedSettingsDto
import site.qgames.game.api.dto.room.GameSettingsLimitsDto
import site.qgames.game.api.dto.room.GameSimpleSettingsDto
import site.qgames.game.api.dto.room.IntRangeDto
import site.qgames.game.api.dto.room.NothingAdvancedSettingsDto
import site.qgames.game.api.dto.room.NothingSettingsLimitsDto
import site.qgames.game.api.dto.room.NothingSimpleSettingsDto
import site.qgames.game.api.dto.room.NothingTimePresetDto
import site.qgames.game.api.dto.room.RoomOptionsDto
import site.qgames.game.api.dto.room.RoomSettingsDto
import site.qgames.game.api.dto.room.TimeControlDto
import site.qgames.game.api.dto.room.UpdateRoomSettingsRequest
import site.qgames.game.model.room.RoomOptionsModel
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.room.RoomSettingsUpdateModel
import site.qgames.games.checkers.CheckersAdvancedSettings
import site.qgames.games.checkers.CheckersSettingsLimits
import site.qgames.games.checkers.CheckersSimpleSettings
import site.qgames.games.checkers.CheckersTime
import site.qgames.games.checkers.CheckersVariant
import site.qgames.games.core.TimeControlSettings
import site.qgames.games.nothing.NothingAdvancedSettings
import site.qgames.games.nothing.NothingSettingsLimits
import site.qgames.games.nothing.NothingSimpleSettings
import site.qgames.games.nothing.NothingTime
import site.qgames.game.api.dto.room.CheckersTime as CheckersTimeDto
import site.qgames.game.api.dto.room.CheckersVariant as CheckersVariantDto
import site.qgames.game.api.dto.room.NothingTime as NothingTimeDto

/**
 * Настройки комнаты: DTO контракта ↔ модель. Simple, advanced и границы конкретной игры — классы её модуля, поэтому
 * новая игра добавляет сюда свои ветки (как и свои DTO в контракт).
 */
@Component
class RoomSettingsResponseMapper {

    fun toModel(request: UpdateRoomSettingsRequest) = RoomSettingsUpdateModel(
        room = request.room?.let { RoomOptionsModel(it.maxSpectators) },
        simple = request.simple?.let(::toModel),
        advanced = request.advanced?.let(::toModel),
    )

    fun toModel(dto: GameSimpleSettingsDto): Any = when (dto) {
        is NothingSimpleSettingsDto -> NothingSimpleSettings(rounds = dto.rounds, time = NothingTime.valueOf(dto.time.name))
        is CheckersSimpleSettingsDto -> CheckersSimpleSettings(
            variant = CheckersVariant.valueOf(dto.variant.name),
            time = CheckersTime.valueOf(dto.time.name),
        )
    }

    fun toModel(dto: GameAdvancedSettingsDto): Any = when (dto) {
        is NothingAdvancedSettingsDto -> NothingAdvancedSettings(rounds = dto.rounds, timeControl = toModel(dto.timeControl))
        is CheckersAdvancedSettingsDto -> CheckersAdvancedSettings(
            variant = CheckersVariant.valueOf(dto.variant.name),
            timeControl = toModel(dto.timeControl),
            menMoveBackward = dto.menMoveBackward,
            menCaptureBackward = dto.menCaptureBackward,
            flyingKings = dto.flyingKings,
            captureRequired = dto.captureRequired,
            maxCaptureRequired = dto.maxCaptureRequired,
            promoteMidCapture = dto.promoteMidCapture,
            removeCapturedAfterMove = dto.removeCapturedAfterMove,
        )
    }

    fun toResponse(model: RoomSettingsModel) = RoomSettingsDto(
        room = RoomOptionsDto(model.room.maxSpectators),
        simple = model.simple?.let(::toSimpleResponse),
        advanced = toAdvancedResponse(model.advanced),
    )

    fun toAdvancedResponse(settings: Any): GameAdvancedSettingsDto = when (settings) {
        is NothingAdvancedSettings -> NothingAdvancedSettingsDto(
            gameCode = GameCode.NOTHING,
            rounds = settings.rounds,
            timeControl = toDto(settings.timeControl),
        )
        is CheckersAdvancedSettings -> CheckersAdvancedSettingsDto(
            gameCode = GameCode.CHECKERS,
            variant = CheckersVariantDto.valueOf(settings.variant.name),
            timeControl = toDto(settings.timeControl),
            menMoveBackward = settings.menMoveBackward,
            menCaptureBackward = settings.menCaptureBackward,
            flyingKings = settings.flyingKings,
            captureRequired = settings.captureRequired,
            maxCaptureRequired = settings.maxCaptureRequired,
            promoteMidCapture = settings.promoteMidCapture,
            removeCapturedAfterMove = settings.removeCapturedAfterMove,
        )
        else -> error("No DTO for advanced settings ${settings::class.qualifiedName}")
    }

    fun toLimitsResponse(limits: Any): GameSettingsLimitsDto = when (limits) {
        is NothingSettingsLimits -> NothingSettingsLimitsDto(
            gameCode = GameCode.NOTHING,
            rounds = toDto(limits.rounds),
            timePresets = limits.timePresets.map {
                NothingTimePresetDto(NothingTimeDto.valueOf(it.time.name), it.baseSeconds, it.incrementSeconds)
            },
        )
        is CheckersSettingsLimits -> CheckersSettingsLimitsDto(
            gameCode = GameCode.CHECKERS,
            variants = limits.variants.map { CheckersVariantDto.valueOf(it.name) },
            timePresets = limits.timePresets.map {
                CheckersTimePresetDto(
                    variant = CheckersVariantDto.valueOf(it.variant.name),
                    time = CheckersTimeDto.valueOf(it.time.name),
                    baseSeconds = it.baseSeconds,
                    incrementSeconds = it.incrementSeconds,
                )
            },
        )
        else -> error("No DTO for game settings limits ${limits::class.qualifiedName}")
    }

    fun toDto(range: IntRange) = IntRangeDto(min = range.first, max = range.last)

    private fun toSimpleResponse(settings: Any): GameSimpleSettingsDto = when (settings) {
        is NothingSimpleSettings -> NothingSimpleSettingsDto(
            gameCode = GameCode.NOTHING,
            rounds = settings.rounds,
            time = NothingTimeDto.valueOf(settings.time.name),
        )
        is CheckersSimpleSettings -> CheckersSimpleSettingsDto(
            gameCode = GameCode.CHECKERS,
            variant = CheckersVariantDto.valueOf(settings.variant.name),
            time = CheckersTimeDto.valueOf(settings.time.name),
        )
        else -> error("No DTO for simple settings ${settings::class.qualifiedName}")
    }

    private fun toModel(dto: TimeControlDto) = TimeControlSettings(dto.enabled, dto.baseSeconds, dto.incrementSeconds)

    private fun toDto(settings: TimeControlSettings) =
        TimeControlDto(settings.enabled, settings.baseSeconds, settings.incrementSeconds)
}
