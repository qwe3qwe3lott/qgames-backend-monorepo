package site.qgames.game.model.game

import site.qgames.game.model.room.RoomSettingsModel

/** Игра целиком: строка каталога и параметры правил из модуля игры. */
data class GameDetailsModel(
    val game: GameModel,
    val rules: GameRulesModel,
)

/** Параметры правил игры для формы настроек комнаты. Собираются из модуля игры. */
data class GameRulesModel(
    val minPlayers: Int,
    val maxPlayers: Int,
    /** Можно ли играть без таймера. */
    val timeControlOptional: Boolean,
    /** Границы запаса часов на партию, секунды. */
    val baseSeconds: IntRange,
    /** Границы добавки за ход, секунды. */
    val incrementSeconds: IntRange,
    /** Границы настроек игры, формат задаёт модуль игры (GameRules.settingsLimits). */
    val settingsLimits: Any,
    val defaultSettings: RoomSettingsModel,
)
