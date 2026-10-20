package site.qgames.game.service.game

import org.springframework.stereotype.Service
import site.qgames.game.model.game.GameInfoModel
import site.qgames.game.model.room.RoomGameType
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.room.RoomTimeControlMode
import site.qgames.game.model.room.RoomTimeControlModel

/** Параметры игры для формы настроек комнаты: строятся из правил игры в [GameRegistry], без БД. */
@Service
class GameInfoService(private val gameRegistry: GameRegistry) {

    fun get(gameType: RoomGameType): GameInfoModel {
        val rules = gameRegistry.rules(gameType)
        val timeControl = rules.timeControl
        return GameInfoModel(
            gameType = gameType,
            minPlayers = rules.minPlayers,
            maxPlayers = rules.maxPlayers,
            // Порядок — как в enum, чтобы ответ был стабильным.
            timeControlModes = RoomTimeControlMode.entries.filter { mode -> timeControl.modes.any { it.name == mode.name } },
            moveSeconds = timeControl.moveSeconds,
            settingsLimits = rules.settingsLimits,
            defaultSettings = RoomSettingsModel(
                timeControl = RoomTimeControlModel(
                    mode = RoomTimeControlMode.valueOf(timeControl.default.mode.name),
                    moveSeconds = timeControl.default.moveSeconds,
                ),
                gameSettings = rules.defaultSettings,
            ),
        )
    }
}
