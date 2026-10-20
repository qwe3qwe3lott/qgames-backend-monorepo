package site.qgames.usergateway.publicapi.dto.room

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import site.qgames.usergateway.publicapi.dto.game.GameCode
import site.qgames.usergateway.publicapi.dto.game.GameCodes

/** Режим контроля времени. Будут добавлены время на раунд и шахматные часы. */
enum class TimeControlMode {
    /** Без таймера. */
    NONE,

    /** Фиксированное время на каждый ход. */
    PER_MOVE,
}

data class TimeControlDto(
    @field:NotNull
    val mode: TimeControlMode,
    /** Секунды на ход: обязательны при PER_MOVE, при NONE — null. Границы задаёт игра. */
    val moveSeconds: Int?,
)

/** Настройки комнаты: правила времени и настройки конкретной игры. */
data class RoomSettingsDto(
    @field:NotNull
    @field:Valid
    val timeControl: TimeControlDto,
    @field:NotNull
    @field:Valid
    val gameSettings: GameSettingsDto,
)

/** Настройки конкретной игры. Вид определяется полем gameCode, оно должно совпадать с игрой комнаты. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "gameCode", visible = true)
@JsonSubTypes(JsonSubTypes.Type(value = NothingSettingsDto::class, name = GameCodes.NOTHING))
@Schema(
    description = "Настройки конкретной игры, вид — по полю gameCode",
    oneOf = [NothingSettingsDto::class],
    discriminatorProperty = "gameCode",
    discriminatorMapping = [DiscriminatorMapping(value = GameCodes.NOTHING, schema = NothingSettingsDto::class)],
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
@JsonSubTypes(JsonSubTypes.Type(value = NothingSettingsLimitsDto::class, name = GameCodes.NOTHING))
@Schema(
    description = "Границы настроек конкретной игры, вид — по полю gameCode",
    oneOf = [NothingSettingsLimitsDto::class],
    discriminatorProperty = "gameCode",
    discriminatorMapping = [DiscriminatorMapping(value = GameCodes.NOTHING, schema = NothingSettingsLimitsDto::class)],
)
sealed interface GameSettingsLimitsDto

/** Границы настроек «игры в ничто». */
data class NothingSettingsLimitsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.NOTHING])
    val gameCode: GameCode,
    val rounds: IntRangeDto,
) : GameSettingsLimitsDto
