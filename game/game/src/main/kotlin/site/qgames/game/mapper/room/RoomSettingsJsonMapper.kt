package site.qgames.game.mapper.room

import org.springframework.stereotype.Component
import site.qgames.game.model.room.RoomTimeControlModel
import tools.jackson.databind.json.JsonMapper

/**
 * Колонка `game_settings` (`room` и снимок в `room_session`): настройки комнаты целиком —
 * `{"timeControl": {baseSeconds, incrementSeconds} | null, "gameSettings": {…}}`. Настройки игры лежат как есть (формат
 * задаёт модуль игры), в модели — JSON-строкой, часы — [RoomTimeControlModel].
 */
@Component
class RoomSettingsJsonMapper(private val jsonMapper: JsonMapper) {

    fun write(timeControl: RoomTimeControlModel?, gameSettings: String): String =
        jsonMapper.writeValueAsString(
            mapOf(TIME_CONTROL to timeControl, GAME_SETTINGS to jsonMapper.readTree(gameSettings)),
        )

    fun timeControl(column: String): RoomTimeControlModel? =
        jsonMapper.readTree(column).get(TIME_CONTROL)
            ?.takeUnless { it.isNull }
            ?.let { jsonMapper.treeToValue(it, RoomTimeControlModel::class.java) }

    /** JSON настроек игры из колонки. */
    fun gameSettings(column: String): String = jsonMapper.writeValueAsString(jsonMapper.readTree(column).get(GAME_SETTINGS))

    private companion object {
        const val TIME_CONTROL = "timeControl"
        const val GAME_SETTINGS = "gameSettings"
    }
}
