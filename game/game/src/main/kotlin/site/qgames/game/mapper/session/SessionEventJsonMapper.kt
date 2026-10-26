package site.qgames.game.mapper.session

import org.springframework.stereotype.Component
import site.qgames.games.core.PathSegment
import site.qgames.games.core.SessionEvent
import site.qgames.games.core.SessionEventMethod
import tools.jackson.databind.json.JsonMapper

/**
 * Данные событий журнала партии ↔ JSON (колонка room_session_event.data) в том же виде, что `data` кадра потока:
 * PUT и PATCH — `{path, value}`, DELETE — `{path}`, INFO — объект значения. Сегмент пути — строка (ключ) или
 * `{"id": "…"}` (элемент массива по id). Значение — JSON-дерево как есть: формат задаёт платформа, модуль или игра.
 */
@Component
class SessionEventJsonMapper(
    private val jsonMapper: JsonMapper,
) {

    fun write(event: SessionEvent): String = jsonMapper.writeValueAsString(data(event))

    fun read(method: String, code: String, json: String): SessionEvent {
        val sessionMethod = SessionEventMethod.valueOf(method)
        val data = jsonMapper.readValue(json, Any::class.java)
        if (sessionMethod == SessionEventMethod.INFO) {
            return SessionEvent(code, sessionMethod, emptyList(), data)
        }
        val fields = data as? Map<*, *> ?: error("Data of a $method event $code must be an object")
        val path = (fields[PATH] as? List<*> ?: error("Data of a $method event $code has no path")).map(::segment)
        return SessionEvent(code, sessionMethod, path, fields[VALUE])
    }

    /** `data` кадра и журнала: PUT и PATCH — `{path, value}`, DELETE — `{path}`, INFO — значение. */
    fun data(event: SessionEvent): Any? = when (event.method) {
        SessionEventMethod.PUT, SessionEventMethod.PATCH -> linkedMapOf(PATH to path(event.path), VALUE to event.value)
        SessionEventMethod.DELETE -> linkedMapOf(PATH to path(event.path))
        SessionEventMethod.INFO -> event.value
    }

    /** Путь в JSON: ключ — строка, элемент массива — `{"id": "…"}`. */
    fun path(path: List<PathSegment>): List<Any> = path.map {
        when (it) {
            is PathSegment.Key -> it.name
            is PathSegment.Id -> mapOf(ID to it.id)
        }
    }

    private fun segment(json: Any?): PathSegment = when (json) {
        is String -> PathSegment.Key(json)
        is Map<*, *> -> PathSegment.Id(json[ID] as? String ?: error("Path segment $json has no string id"))
        else -> error("Unknown path segment $json")
    }

    companion object {
        private const val PATH = "path"
        private const val VALUE = "value"
        private const val ID = "id"
    }
}
