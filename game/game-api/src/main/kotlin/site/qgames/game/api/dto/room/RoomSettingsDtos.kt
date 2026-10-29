package site.qgames.game.api.dto.room

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.game.GameCodes
import site.qgames.game.api.dto.session.CheckersColor

/**
 * Настройки комнаты из трёх групп:
 * - [room] — сама комната (лимит наблюдателей), меняется в любой момент;
 * - [advanced] — корневые правила игры: по ним идёт партия;
 * - [simple] — короткий выбор для игроков (вариант, пресет времени), из него сервер выводит advanced.
 *
 * Режим комнаты — simple или advanced: simple есть только в simple-режиме, advanced есть всегда (в simple-режиме он
 * выведен из simple).
 */
data class RoomSettingsDto(
    @field:Valid
    val room: RoomOptionsDto,
    /** null — комната в advanced-режиме. */
    @field:Valid
    val simple: GameSimpleSettingsDto?,
    @field:Valid
    val advanced: GameAdvancedSettingsDto,
)

/**
 * Замена групп настроек комнаты: переданная группа заменяется целиком, null — группа не меняется. simple и advanced
 * взаимоисключающие: simple переводит комнату в simple-режим (advanced выводится из него), advanced — в advanced-режим.
 * Нужна хотя бы одна группа.
 */
data class UpdateRoomSettingsRequest(
    @field:Valid
    val room: RoomOptionsDto? = null,
    @field:Valid
    val simple: GameSimpleSettingsDto? = null,
    @field:Valid
    val advanced: GameAdvancedSettingsDto? = null,
)

/** Настройки самой комнаты. Границы — GameRulesDto.spectators. */
data class RoomOptionsDto(
    /** Сколько наблюдателей может войти. Уже вошедшие при понижении лимита остаются. */
    @field:NotNull
    val maxSpectators: Int,
)

/**
 * Часы advanced-настроек: у каждого игрока запас на всю партию и добавочное время bonusSeconds вида bonusType (0 — без
 * него). Значения заполнены и у выключенных часов. Границы задаёт игра (TimeControlLimitsDto). Simple-настройки всегда
 * дают инкремент.
 */
data class TimeControlDto(
    /** false — партия без таймера (если игра разрешает, TimeControlLimitsDto.optional). */
    @field:NotNull
    val enabled: Boolean,
    @field:NotNull
    val baseSeconds: Int,
    @field:NotNull
    val bonusType: TimeBonusType,
    @field:NotNull
    val bonusSeconds: Int,
)

/** Вид добавочного времени часов. */
enum class TimeBonusType {
    /** Инкремент (Фишер): после своего хода игрок получает к запасу bonusSeconds. */
    INCREMENT,

    /** Задержка: в начале хода сначала тратится bonusSeconds, затем запас; неистраченная задержка не копится. */
    DELAY,
}

/** Simple-настройки конкретной игры. Вид определяется полем gameCode, оно должно совпадать с игрой комнаты. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "gameCode", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = NothingSimpleSettingsDto::class, name = GameCodes.NOTHING),
    JsonSubTypes.Type(value = CheckersSimpleSettingsDto::class, name = GameCodes.CHECKERS),
)
@Schema(
    description = "Simple-настройки конкретной игры, вид — по полю gameCode",
    oneOf = [NothingSimpleSettingsDto::class, CheckersSimpleSettingsDto::class],
    discriminatorProperty = "gameCode",
    discriminatorMapping = [
        DiscriminatorMapping(value = GameCodes.NOTHING, schema = NothingSimpleSettingsDto::class),
        DiscriminatorMapping(value = GameCodes.CHECKERS, schema = CheckersSimpleSettingsDto::class),
    ],
)
sealed interface GameSimpleSettingsDto

/** Advanced-настройки конкретной игры. Вид определяется полем gameCode, оно должно совпадать с игрой комнаты. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "gameCode", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = NothingAdvancedSettingsDto::class, name = GameCodes.NOTHING),
    JsonSubTypes.Type(value = CheckersAdvancedSettingsDto::class, name = GameCodes.CHECKERS),
)
@Schema(
    description = "Advanced-настройки конкретной игры, вид — по полю gameCode",
    oneOf = [NothingAdvancedSettingsDto::class, CheckersAdvancedSettingsDto::class],
    discriminatorProperty = "gameCode",
    discriminatorMapping = [
        DiscriminatorMapping(value = GameCodes.NOTHING, schema = NothingAdvancedSettingsDto::class),
        DiscriminatorMapping(value = GameCodes.CHECKERS, schema = CheckersAdvancedSettingsDto::class),
    ],
)
sealed interface GameAdvancedSettingsDto

/** Пресет времени «игры в ничто». */
enum class NothingTime {
    /** Без таймера. */
    NONE,

    /** 30 с + 5 с за ход. */
    CLASSIC,
}

/** Simple-настройки «игры в ничто». */
data class NothingSimpleSettingsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.NOTHING])
    val gameCode: GameCode,
    /** Число кругов партии. */
    val rounds: Int,
    /** Пресет времени; часы пресетов — NothingSettingsLimitsDto.timePresets. */
    val time: NothingTime,
) : GameSimpleSettingsDto

/** Advanced-настройки «игры в ничто». */
data class NothingAdvancedSettingsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.NOTHING])
    val gameCode: GameCode,
    /** Число кругов партии. */
    val rounds: Int,
    @field:Valid
    val timeControl: TimeControlDto,
) : GameAdvancedSettingsDto

/**
 * Вариант правил шашек simple-настроек. Доступные варианты — CheckersSettingsLimitsDto.variants, их правила для кнопок
 * пресетов advanced — CheckersSettingsLimitsDto.rulePresets.
 */
enum class CheckersVariant {
    /** Русские шашки. */
    RUSSIAN,

    /** Международные (100-клеточные) шашки. */
    INTERNATIONAL,

    /** Английские шашки (чекерс). */
    ENGLISH,
}

/** Пресет времени шашек: официальный контроль времени варианта или без таймера. */
enum class CheckersTime {
    /** Без таймера. */
    NONE,

    /** Классика. */
    CLASSIC,

    /** Быстрые шашки. */
    RAPID,

    /** Блиц. */
    BLITZ,
}

/** Simple-настройки шашек. */
data class CheckersSimpleSettingsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.CHECKERS])
    val gameCode: GameCode,
    /** Вариант правил: один из доступных (CheckersSettingsLimitsDto.variants). */
    val variant: CheckersVariant,
    /** Пресет времени; часы пресетов по вариантам — CheckersSettingsLimitsDto.timePresets. */
    val time: CheckersTime,
) : GameSimpleSettingsDto

/** Правила окончаний шашек: при каком соотношении сил и через сколько ходов партия — ничья. */
enum class CheckersEndgameRule {
    /** Особых правил окончаний нет (как у английских). */
    NONE,

    /** Правила русских шашек. */
    RUSSIAN,

    /** Правила международных шашек (FMJD). */
    INTERNATIONAL,
}

/**
 * Advanced-настройки шашек: доска, первый ход, правила ничьих, часы и правила хода. Варианта нет: его правила
 * подставляют кнопки пресетов (CheckersSettingsLimitsDto.rulePresets). Итоговые правила партии — meta.advancedRules
 * (CheckersAdvancedRulesDto).
 */
data class CheckersAdvancedSettingsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.CHECKERS])
    val gameCode: GameCode,
    /** Размер доски: один из CheckersSettingsLimitsDto.boardSizes. */
    val boardSize: Int,
    /** Кто ходит первым. */
    val firstColor: CheckersColor,
    /** Правила окончаний. */
    val endgameRule: CheckersEndgameRule,
    /**
     * Ничья после стольких ходов подряд (обеих сторон в сумме) только дамками без взятий: одно из
     * CheckersSettingsLimitsDto.drawQuietMoves.
     */
    val drawQuietMoves: Int,
    @field:Valid
    val timeControl: TimeControlDto,
    /** Тихий ход простой назад. */
    val menMoveBackward: Boolean,
    /** Взятие простой назад. */
    val menCaptureBackward: Boolean,
    /** Дамка ходит и бьёт на любое число клеток. */
    val flyingKings: Boolean,
    /** Взятие обязательно. */
    val captureRequired: Boolean,
    /** Брать только ветку с наибольшим числом сбитых. */
    val maxCaptureRequired: Boolean,
    /** Ставшая дамкой посреди взятия бьёт дальше как дамка. */
    val promoteMidCapture: Boolean,
    /** Турецкий удар: сбитые снимаются после хода, через сбитую шашку второй раз не прыгать. */
    val removeCapturedAfterMove: Boolean,
) : GameAdvancedSettingsDto

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
    /** Часы пресетов времени (кроме NONE): для подписей в форме. */
    val timePresets: List<NothingTimePresetDto>,
) : GameSettingsLimitsDto

/** Часы пресета времени «игры в ничто». */
data class NothingTimePresetDto(
    val time: NothingTime,
    val baseSeconds: Int,
    val bonusSeconds: Int,
)

/** Границы настроек шашек. */
data class CheckersSettingsLimitsDto(
    @field:Schema(type = "string", allowableValues = [GameCodes.CHECKERS])
    val gameCode: GameCode,
    /** Варианты правил, которые можно выбрать. */
    val variants: List<CheckersVariant>,
    /** Часы пресетов времени по вариантам (кроме NONE): для подписей и кнопок времени в форме. */
    val timePresets: List<CheckersTimePresetDto>,
    /** Размеры доски, которые можно выбрать в advanced. */
    val boardSizes: List<Int>,
    /** Значения CheckersAdvancedSettingsDto.drawQuietMoves, которые можно выбрать. */
    val drawQuietMoves: List<Int>,
    /** Правила вариантов: кнопка пресета в форме advanced подставляет их в поля. */
    val rulePresets: List<CheckersRulePresetDto>,
) : GameSettingsLimitsDto

/** Правила варианта для кнопки пресета advanced: все поля CheckersAdvancedSettingsDto, кроме часов. */
data class CheckersRulePresetDto(
    val variant: CheckersVariant,
    val boardSize: Int,
    val firstColor: CheckersColor,
    val endgameRule: CheckersEndgameRule,
    val drawQuietMoves: Int,
    val menMoveBackward: Boolean,
    val menCaptureBackward: Boolean,
    val flyingKings: Boolean,
    val captureRequired: Boolean,
    val maxCaptureRequired: Boolean,
    val promoteMidCapture: Boolean,
    val removeCapturedAfterMove: Boolean,
)

/** Официальный контроль времени варианта правил: часы пресета. */
data class CheckersTimePresetDto(
    val variant: CheckersVariant,
    val time: CheckersTime,
    val baseSeconds: Int,
    val bonusSeconds: Int,
)
