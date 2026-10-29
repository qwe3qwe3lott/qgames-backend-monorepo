package site.qgames.common.docpatch

/**
 * Общий применитель операций ([PatchOperation]) к JSON-документу: документ партии `{status, meta, state}` (полный и
 * проекции зрителей), документ комнаты в потоке комнаты. Своих редьюсеров ни у игр, ни у комнаты нет. Документ —
 * JSON-дерево (Map, List, String, Number, Boolean, null); исходный документ не меняется, возвращается новый.
 *
 * - PUT — заменить или создать значение по пути. Промежуточные сегменты должны существовать. Если последний сегмент
 *   `{id}` и такого элемента нет, значение (объект с тем же id) добавляется в конец массива. Пустой путь — весь
 *   документ;
 * - PATCH — значение — объект, его поля заменяют поля объекта по пути (неглубоко: вложенные значения — целиком, null —
 *   как значение). Путь должен существовать;
 * - DELETE — удалить поле объекта или элемент массива по id. Путь должен существовать и быть непустым;
 * - INFO — документ не меняется.
 *
 * Несуществующий путь или неподходящий тип — [DocumentPatchException]: на сервере это ошибка в коде, который породил
 * операцию.
 */
object DocumentPatch {

    fun apply(document: Map<String, Any?>?, operation: PatchOperation): Map<String, Any?> {
        if (operation.method == PatchMethod.INFO) {
            return document ?: fail(operation, "the document is empty")
        }
        if (document == null && !(operation.method == PatchMethod.PUT && operation.path.isEmpty())) {
            fail(operation, "the first operation must replace the whole document (PUT [])")
        }
        val result = when (operation.method) {
            PatchMethod.PUT -> put(document, operation.path, 0, operation)
            PatchMethod.PATCH -> patch(document, operation.path, 0, operation)
            PatchMethod.DELETE -> delete(document, operation.path, 0, operation)
            PatchMethod.INFO -> document
        }
        return objectOf(result, operation, "the document")
    }

    fun fold(document: Map<String, Any?>?, operations: List<PatchOperation>): Map<String, Any?>? =
        operations.fold(document) { current, operation -> apply(current, operation) }

    private fun put(node: Any?, path: List<PathSegment>, index: Int, operation: PatchOperation): Any? {
        if (index == path.size) return operation.value
        val last = index == path.lastIndex
        return when (val segment = path[index]) {
            is PathSegment.Key -> {
                val obj = objectOf(node, operation, at(path, index))
                if (!last && segment.name !in obj) fail(operation, "no key at ${at(path, index + 1)}")
                obj.with(segment.name, put(obj[segment.name], path, index + 1, operation))
            }
            is PathSegment.Id -> {
                val array = arrayOf(node, operation, at(path, index))
                val position = array.indexOfId(segment.id)
                when {
                    position >= 0 -> array.replaced(position, put(array[position], path, index + 1, operation))
                    !last -> fail(operation, "no element at ${at(path, index + 1)}")
                    (operation.value as? Map<*, *>)?.get(ID) != segment.id ->
                        fail(operation, "a new element at ${at(path, index + 1)} must be an object with the same id")
                    else -> array + listOf(operation.value)
                }
            }
        }
    }

    private fun patch(node: Any?, path: List<PathSegment>, index: Int, operation: PatchOperation): Any? {
        if (index == path.size) {
            val target = objectOf(node, operation, at(path, index))
            val fields = objectOf(operation.value, operation, "the value")
            return LinkedHashMap(target).apply { putAll(fields) }
        }
        return descend(node, path, index, operation) { child -> patch(child, path, index + 1, operation) }
    }

    private fun delete(node: Any?, path: List<PathSegment>, index: Int, operation: PatchOperation): Any? {
        if (path.isEmpty()) fail(operation, "the whole document cannot be deleted")
        if (index < path.lastIndex) {
            return descend(node, path, index, operation) { child -> delete(child, path, index + 1, operation) }
        }
        return when (val segment = path[index]) {
            is PathSegment.Key -> {
                val obj = objectOf(node, operation, at(path, index))
                if (segment.name !in obj) fail(operation, "no key at ${at(path, index + 1)}")
                LinkedHashMap(obj).apply { remove(segment.name) }
            }
            is PathSegment.Id -> {
                val array = arrayOf(node, operation, at(path, index))
                val position = array.indexOfId(segment.id)
                if (position < 0) fail(operation, "no element at ${at(path, index + 1)}")
                array.filterIndexed { i, _ -> i != position }
            }
        }
    }

    /** Заменяет существующий дочерний узел [path] [index] результатом [change]. */
    private fun descend(
        node: Any?,
        path: List<PathSegment>,
        index: Int,
        operation: PatchOperation,
        change: (Any?) -> Any?,
    ): Any? = when (val segment = path[index]) {
        is PathSegment.Key -> {
            val obj = objectOf(node, operation, at(path, index))
            if (segment.name !in obj) fail(operation, "no key at ${at(path, index + 1)}")
            obj.with(segment.name, change(obj[segment.name]))
        }
        is PathSegment.Id -> {
            val array = arrayOf(node, operation, at(path, index))
            val position = array.indexOfId(segment.id)
            if (position < 0) fail(operation, "no element at ${at(path, index + 1)}")
            array.replaced(position, change(array[position]))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectOf(node: Any?, operation: PatchOperation, where: String): Map<String, Any?> =
        node as? Map<String, Any?> ?: fail(operation, "$where is not an object")

    private fun arrayOf(node: Any?, operation: PatchOperation, where: String): List<Any?> =
        node as? List<Any?> ?: fail(operation, "$where is not an array")

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

    private fun fail(operation: PatchOperation, reason: String): Nothing =
        throw DocumentPatchException("Cannot apply ${operation.method} ${operation.code}: $reason")

    private const val ID = "id"
}

/** Операцию нельзя применить к документу: несуществующий путь или неподходящий тип. */
class DocumentPatchException(message: String) : IllegalStateException(message)
