package site.qgames.game.mapper.room

import org.springframework.stereotype.Component
import site.qgames.game.model.room.RoomOptionsModel
import tools.jackson.databind.json.JsonMapper

/**
 * Колонка `room.settings`: настройки комнаты целиком — `{"room": {maxSpectators}, "simple": {…} | null,
 * "advanced": {…}}`. Настройки игры лежат как есть (формат задаёт модуль игры), в модели — JSON-строками, настройки
 * комнаты — [RoomOptionsModel].
 */
@Component
class RoomSettingsJsonMapper(private val jsonMapper: JsonMapper) {

    fun write(options: RoomOptionsModel, simple: String?, advanced: String): String =
        jsonMapper.writeValueAsString(
            mapOf(
                ROOM to options,
                SIMPLE to simple?.let(jsonMapper::readTree),
                ADVANCED to jsonMapper.readTree(advanced),
            ),
        )

    fun options(column: String): RoomOptionsModel = jsonMapper.treeToValue(jsonMapper.readTree(column).get(ROOM), RoomOptionsModel::class.java)

    /** JSON simple-настроек; null — advanced-режим. */
    fun simple(column: String): String? =
        jsonMapper.readTree(column).get(SIMPLE)?.takeUnless { it.isNull }?.let(jsonMapper::writeValueAsString)

    fun advanced(column: String): String = jsonMapper.writeValueAsString(jsonMapper.readTree(column).get(ADVANCED))

    private companion object {
        const val ROOM = "room"
        const val SIMPLE = "simple"
        const val ADVANCED = "advanced"
    }
}
