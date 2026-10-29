package site.qgames.game.mapper.session

import org.springframework.stereotype.Component
import site.qgames.common.docpatch.PatchMethod
import site.qgames.common.docpatch.PathSegment
import site.qgames.game.mapper.patch.PatchJsonMapper
import site.qgames.games.core.SessionEvent
import tools.jackson.databind.json.JsonMapper

/**
 * Данные событий журнала партии ↔ JSON (колонка room_session_event.data) в том же виде, что `data` кадра потока
 * ([PatchJsonMapper]). Значение — JSON-дерево как есть: формат задаёт платформа, модуль или игра.
 */
@Component
class SessionEventJsonMapper(
    private val jsonMapper: JsonMapper,
    private val patchJsonMapper: PatchJsonMapper,
) {

    fun write(event: SessionEvent): String = jsonMapper.writeValueAsString(patchJsonMapper.data(event))

    fun read(method: String, code: String, json: String): SessionEvent {
        val patchMethod = PatchMethod.valueOf(method)
        val (path, value) = patchJsonMapper.read(patchMethod, code, jsonMapper.readValue(json, Any::class.java))
        return SessionEvent(code, patchMethod, path, value)
    }

    /** Путь в JSON: ключ — строка, элемент массива — `{"id": "…"}`. */
    fun path(path: List<PathSegment>): List<Any> = patchJsonMapper.path(path)
}
