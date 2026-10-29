package site.qgames.game.mapper.patch

import org.springframework.stereotype.Component
import site.qgames.common.docpatch.PatchMethod
import site.qgames.common.docpatch.PatchOperation
import site.qgames.common.docpatch.PathSegment

/**
 * `data` событий-патчей (журнал партии, кадры потоков партии и комнаты, уведомление room_events) ↔ JSON-дерево:
 * PUT и PATCH — `{path, value}`, DELETE — `{path}`, INFO — объект значения. Сегмент пути — строка (ключ) или
 * `{"id": "…"}` (элемент массива по id).
 */
@Component
class PatchJsonMapper {

    /** `data` операции: PUT и PATCH — `{path, value}`, DELETE — `{path}`, INFO — значение. */
    fun data(operation: PatchOperation): Any? = when (operation.method) {
        PatchMethod.PUT, PatchMethod.PATCH -> linkedMapOf(PATH to path(operation.path), VALUE to operation.value)
        PatchMethod.DELETE -> linkedMapOf(PATH to path(operation.path))
        PatchMethod.INFO -> operation.value
    }

    /** Путь и значение из `data` операции [method] (обратное к [data]). */
    fun read(method: PatchMethod, code: String, data: Any?): Pair<List<PathSegment>, Any?> {
        if (method == PatchMethod.INFO) {
            return emptyList<PathSegment>() to data
        }
        val fields = data as? Map<*, *> ?: error("Data of a $method event $code must be an object")
        val path = (fields[PATH] as? List<*> ?: error("Data of a $method event $code has no path")).map(::segment)
        return path to fields[VALUE]
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
