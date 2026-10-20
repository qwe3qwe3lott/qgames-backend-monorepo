package site.qgames.usergateway.publicapi.dto.game

import site.qgames.usergateway.publicapi.dto.room.GameSettingsLimitsDto
import site.qgames.usergateway.publicapi.dto.room.GameType
import site.qgames.usergateway.publicapi.dto.room.IntRangeDto
import site.qgames.usergateway.publicapi.dto.room.RoomSettingsDto
import site.qgames.usergateway.publicapi.dto.room.TimeControlMode

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
