package site.qgames.game.api.dto.room

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.game.GameCodes

/**
 * Шахматные часы: у каждого игрока запас на всю партию, после своего хода он получает добавку (0 — без добавки).
 * Границы задаёт игра (TimeControlLimitsDto).
 */
data class TimeControlDto(
    @field:NotNull
    val baseSeconds: Int,
    @field:NotNull
    val incrementSeconds: Int,
)

/** Настройки комнаты: часы и настройки конкретной игры. */
data class RoomSettingsDto(
    /** Шахматные часы; null — без таймера (если игра разрешает, TimeControlLimitsDto.optional). */
    @field:Valid
    val timeControl: TimeControlDto?,
    @field:NotNull
    @field:Valid
    val gameSettings: GameSettingsDto,
)

/** Настройки конкретной игры. Вид определяется полем gameCode, оно должно совпадать с игрой комнаты. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "gameCode", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = NothingSettingsDto::class, name = GameCodes.NOTHING),
    JsonSubTypes.Type(value = CheckersSettingsDto::class, name = GameCodes.CHECKERS),
)
@Schema(
    description = "Настройки конкретной игры, вид — по полю gameCode",
    oneOf = [NothingSettingsDto::class, CheckersSettingsDto::class],
    discriminatorProperty = "gameCode",
    discriminatorMapping = [
        DiscriminatorMapping(value = GameCodes.NOTHING, schema = NothingSettingsDto::class),
        DiscriminatorMapping(value = GameCodes.CHECKERS, schema = CheckersSettingsDto::class),
    ],
)
sealed interface GameSettingsDto

/** Настройки «игры в ничто». */
data class NothingSettingsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.NOTHING])
    val gameCode: GameCode,
    /** Число кругов партии. */
    val rounds: Int,
) : GameSettingsDto

/** Границы целочисленного параметра, включительно. */
data class IntRangeDto(
    val min: Int,
    val max: Int,
)

/** Границы настроек конкретной игры, вид — по полю gameCode. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "gameCode", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = NothingSettingsLimitsDto::class, name = GameCodes.NOTHING),
    JsonSubTypes.Type(value = CheckersSettingsLimitsDto::class, name = GameCodes.CHECKERS),
)
@Schema(
    description = "Границы настроек конкретной игры, вид — по полю gameCode",
    oneOf = [NothingSettingsLimitsDto::class, CheckersSettingsLimitsDto::class],
    discriminatorProperty = "gameCode",
    discriminatorMapping = [
        DiscriminatorMapping(value = GameCodes.NOTHING, schema = NothingSettingsLimitsDto::class),
        DiscriminatorMapping(value = GameCodes.CHECKERS, schema = CheckersSettingsLimitsDto::class),
    ],
)
sealed interface GameSettingsLimitsDto

/** Границы настроек «игры в ничто». */
data class NothingSettingsLimitsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.NOTHING])
    val gameCode: GameCode,
    val rounds: IntRangeDto,
) : GameSettingsLimitsDto

/** Вариант правил шашек. Доступные в настройках варианты — в границах настроек игры (CheckersSettingsLimitsDto.variants). */
enum class CheckersVariant {
    /** Русские шашки. */
    RUSSIAN,

    /** Международные (100-клеточные) шашки. */
    INTERNATIONAL,

    /** Английские шашки (чекерс). */
    ENGLISH,
}

/** Настройки шашек. */
data class CheckersSettingsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.CHECKERS])
    val gameCode: GameCode,
    /** Вариант правил: один из доступных (CheckersSettingsLimitsDto.variants). */
    val variant: CheckersVariant,
) : GameSettingsDto

/** Границы настроек шашек. */
data class CheckersSettingsLimitsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.CHECKERS])
    val gameCode: GameCode,
    /** Варианты правил, которые можно выбрать. */
    val variants: List<CheckersVariant>,
    /** Официальные контроли времени по вариантам: готовые значения шахматных часов для формы. */
    val timePresets: List<CheckersTimePresetDto>,
) : GameSettingsLimitsDto

/** Вид официального контроля времени. */
enum class CheckersTimePreset {
    /** Классика. */
    CLASSIC,

    /** Быстрые шашки. */
    RAPID,

    /** Блиц. */
    BLITZ,
}

/** Официальный контроль времени варианта правил: значения шахматных часов (TimeControlDto). */
data class CheckersTimePresetDto(
    val variant: CheckersVariant,
    val preset: CheckersTimePreset,
    val baseSeconds: Int,
    val incrementSeconds: Int,
)
