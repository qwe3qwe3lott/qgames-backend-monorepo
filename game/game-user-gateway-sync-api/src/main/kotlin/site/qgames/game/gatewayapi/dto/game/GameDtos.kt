package site.qgames.game.gatewayapi.dto.game

import site.qgames.game.gatewayapi.dto.room.GameSettingsLimitsDto
import site.qgames.game.gatewayapi.dto.room.GameType
import site.qgames.game.gatewayapi.dto.room.IntRangeDto
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode

/** Параметры игры для формы настроек комнаты. */
data class GameInfoResponse(
    val gameType: GameType,
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
