package site.qgames.game.model.game

import site.qgames.game.model.room.RoomGameType
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.room.RoomTimeControlMode

/** Параметры игры для формы настроек комнаты. Собирается из правил игры. */
data class GameInfoModel(
    val gameType: RoomGameType,
    val minPlayers: Int,
    val maxPlayers: Int,
    val timeControlModes: List<RoomTimeControlMode>,
    /** Границы секунд на ход для PER_MOVE. */
    val moveSeconds: IntRange,
    /** Границы настроек игры, формат задаёт модуль игры (GameRules.settingsLimits). */
    val settingsLimits: Any,
    val defaultSettings: RoomSettingsModel,
)
