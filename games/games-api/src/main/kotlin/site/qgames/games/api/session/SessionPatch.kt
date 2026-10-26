package site.qgames.games.api.session

/**
 * Общий применитель событий партии к документу `{status, meta, state}` — один для полного состояния и для проекций
 * зрителей (у игр и модулей своих редьюсеров нет). Документ — JSON-дерево (Map, List, String, Number, Boolean, null);
 * исходный документ не меняется, возвращается новый.
 *
 * - PUT — заменить или создать значение по пути. Промежуточные сегменты должны существовать. Если последний сегмент
 *   `{id}` и такого элемента нет, значение (объект с тем же id) добавляется в конец массива. Пустой путь — весь
 *   документ;
 * - PATCH — значение — объект, его поля заменяют поля объекта по пути (неглубоко: вложенные значения — целиком, null —
 *   как значение). Путь должен существовать;
 * - DELETE — удалить поле объекта или элемент массива по id. Путь должен существовать и быть непустым;
 * - INFO — документ не меняется.
 *
 * Несуществующий путь или неподходящий тип — [SessionPatchException]: на сервере это ошибка в коде модуля или игры.
 */
object SessionPatch {

    fun apply(document: Map<String, Any?>?, event: SessionEvent): Map<String, Any?> {
        if (event.method == SessionEventMethod.INFO) {
            return document ?: fail(event, "the document is empty")
        }
        if (document == null && !(event.method == SessionEventMethod.PUT && event.path.isEmpty())) {
            fail(event, "the first event must replace the whole document (PUT [])")
        }
        val result = when (event.method) {
            SessionEventMethod.PUT -> put(document, event.path, 0, event)
            SessionEventMethod.PATCH -> patch(document, event.path, 0, event)
            SessionEventMethod.DELETE -> delete(document, event.path, 0, event)
            SessionEventMethod.INFO -> document
        }
        return objectOf(result, event, "the document")
    }

    fun fold(document: Map<String, Any?>?, events: List<SessionEvent>): Map<String, Any?>? =
        events.fold(document) { current, event -> apply(current, event) }

    private fun put(node: Any?, path: List<PathSegment>, index: Int, event: SessionEvent): Any? {
        if (index == path.size) return event.value
        val last = index == path.lastIndex
        return when (val segment = path[index]) {
            is PathSegment.Key -> {
                val obj = objectOf(node, event, at(path, index))
                if (!last && segment.name !in obj) fail(event, "no key at ${at(path, index + 1)}")
                obj.with(segment.name, put(obj[segment.name], path, index + 1, event))
            }
            is PathSegment.Id -> {
                val array = arrayOf(node, event, at(path, index))
                val position = array.indexOfId(segment.id)
                when {
                    position >= 0 -> array.replaced(position, put(array[position], path, index + 1, event))
                    !last -> fail(event, "no element at ${at(path, index + 1)}")
                    (event.value as? Map<*, *>)?.get(ID) != segment.id ->
                        fail(event, "a new element at ${at(path, index + 1)} must be an object with the same id")
                    else -> array + listOf(event.value)
                }
            }
        }
    }

    private fun patch(node: Any?, path: List<PathSegment>, index: Int, event: SessionEvent): Any? {
        if (index == path.size) {
            val target = objectOf(node, event, at(path, index))
            val fields = objectOf(event.value, event, "the value")
            return LinkedHashMap(target).apply { putAll(fields) }
        }
        return descend(node, path, index, event) { child -> patch(child, path, index + 1, event) }
    }

    private fun delete(node: Any?, path: List<PathSegment>, index: Int, event: SessionEvent): Any? {
        if (path.isEmpty()) fail(event, "the whole document cannot be deleted")
        if (index < path.lastIndex) {
            return descend(node, path, index, event) { child -> delete(child, path, index + 1, event) }
        }
        return when (val segment = path[index]) {
            is PathSegment.Key -> {
                val obj = objectOf(node, event, at(path, index))
                if (segment.name !in obj) fail(event, "no key at ${at(path, index + 1)}")
                LinkedHashMap(obj).apply { remove(segment.name) }
            }
            is PathSegment.Id -> {
                val array = arrayOf(node, event, at(path, index))
                val position = array.indexOfId(segment.id)
                if (position < 0) fail(event, "no element at ${at(path, index + 1)}")
                array.filterIndexed { i, _ -> i != position }
            }
        }
    }

    /** Заменяет существующий дочерний узел [path] [index] результатом [change]. */
    private fun descend(node: Any?, path: List<PathSegment>, index: Int, event: SessionEvent, change: (Any?) -> Any?): Any? =
        when (val segment = path[index]) {
            is PathSegment.Key -> {
                val obj = objectOf(node, event, at(path, index))
                if (segment.name !in obj) fail(event, "no key at ${at(path, index + 1)}")
                obj.with(segment.name, change(obj[segment.name]))
            }
            is PathSegment.Id -> {
                val array = arrayOf(node, event, at(path, index))
                val position = array.indexOfId(segment.id)
                if (position < 0) fail(event, "no element at ${at(path, index + 1)}")
                array.replaced(position, change(array[position]))
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun objectOf(node: Any?, event: SessionEvent, where: String): Map<String, Any?> =
        node as? Map<String, Any?> ?: fail(event, "$where is not an object")

    private fun arrayOf(node: Any?, event: SessionEvent, where: String): List<Any?> =
        node as? List<Any?> ?: fail(event, "$where is not an array")

    private fun Map<String, Any?>.with(key: String, value: Any?): Map<String, Any?> = LinkedHashMap(this).apply { put(key, value) }

    private fun List<Any?>.indexOfId(id: String): Int = indexOfFirst { (it as? Map<*, *>)?.get(ID) == id }

    private fun List<Any?>.replaced(position: Int, value: Any?): List<Any?> = toMutableList().apply { set(position, value) }

    private fun at(path: List<PathSegment>, length: Int): String =
        path.take(length).joinToString(", ", "[", "]") {
            when (it) {
                is PathSegment.Key -> "\"${it.name}\""
                is PathSegment.Id -> "{id: ${it.id}}"
            }
        }

    private fun fail(event: SessionEvent, reason: String): Nothing =
        throw SessionPatchException("Cannot apply ${event.method} ${event.code}: $reason")

    private const val ID = "id"
}

/** Событие нельзя применить к документу: несуществующий путь или неподходящий тип. */
class SessionPatchException(message: String) : IllegalStateException(message)
