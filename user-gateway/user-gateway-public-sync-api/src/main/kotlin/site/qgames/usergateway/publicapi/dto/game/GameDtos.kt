package site.qgames.usergateway.publicapi.dto.game

import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.room.GameSettingsLimitsDto
import site.qgames.game.gatewayapi.dto.room.IntRangeDto
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode

// Каталог игр пока описан собственными DTO gateway. Общие с комнатами типы (код игры, настройки и их границы) — из
// контракта game: ручки комнат отдают его DTO как есть, и одноимённые схемы в OpenAPI не должны расходиться.

/** Статус публикации игры. Неопубликованные и принудительно отключённые игры наружу не выдаются (GAME_NOT_FOUND). */
enum class GamePublishStatus {
    /** Доступна: можно создавать комнаты и начинать партии. */
    PUBLISHED,

    /** Временно недоступна: новые комнаты и партии запрещены (GAME_UNAVAILABLE), идущие партии продолжаются. */
    DISABLED,

    /** Скрыта из каталога, но доступна по прямой ссылке. */
    HIDDEN,
}

/** Каталог игр в порядке показа. */
data class GameListResponse(
    val games: List<GameSummaryDto>,
)

/** Игра в каталоге. */
data class GameSummaryDto(
    val code: GameCode,
    val title: String,
    val description: String,
    val publishStatus: GamePublishStatus,
)

/** Игра целиком: описание из каталога и параметры правил для формы настроек комнаты. */
data class GameDetailsResponse(
    val code: GameCode,
    val title: String,
    val description: String,
    val publishStatus: GamePublishStatus,
    val rules: GameRulesDto,
)

/** Параметры правил игры: по ним строится форма создания комнаты и смены настроек. */
data class GameRulesDto(
    val minPlayers: Int,
    val maxPlayers: Int,
    val timeControl: TimeControlLimitsDto,
    val gameSettingsLimits: GameSettingsLimitsDto,
    /** Настройки, которыми заполняется форма создания комнаты. */
    val defaultSettings: RoomSettingsDto,
)

/** Какие правила времени разрешает игра. */
data class TimeControlLimitsDto(
    val modes: List<TimeControlMode>,
    /** Границы времени на ход в секундах (для PER_MOVE). */
    val moveSeconds: IntRangeDto,
)
