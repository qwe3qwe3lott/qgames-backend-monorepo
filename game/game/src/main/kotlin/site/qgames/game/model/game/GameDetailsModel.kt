package site.qgames.game.model.game

import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.room.RoomTimeControlMode

/** Игра целиком: строка каталога и параметры правил из модуля игры. */
data class GameDetailsModel(
    val game: GameModel,
    val rules: GameRulesModel,
)

/** Параметры правил игры для формы настроек комнаты. Собираются из модуля игры. */
data class GameRulesModel(
    val minPlayers: Int,
    val maxPlayers: Int,
    val timeControlModes: List<RoomTimeControlMode>,
    /** Границы секунд на ход для PER_MOVE. */
    val moveSeconds: IntRange,
    /** Границы настроек игры, формат задаёт модуль игры (GameRules.settingsLimits). */
    val settingsLimits: Any,
    val defaultSettings: RoomSettingsModel,
)
