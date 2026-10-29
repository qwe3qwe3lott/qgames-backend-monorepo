package site.qgames.common.docpatch

import java.util.UUID

/**
 * Операция над JSON-документом ([DocumentPatch]): событие партии, событие потока комнаты.
 *
 * - [code] — что произошло (`player-passed`, `member-joined`…), для журнала и логов. Документ по коду не меняется —
 *   только по [method];
 * - [method] — как меняется документ;
 * - [path] — куда: ключи объектов и элементы массивов по id ([PathSegment]); у INFO пустой;
 * - [value] — новое значение (PUT), поля для слияния (PATCH), данные для журнала (INFO); у DELETE null. Значение —
 *   JSON-дерево (Map, List, String, Number, Boolean, null) или объект, который сериализуется в него.
 */
interface PatchOperation {
    val code: String
    val method: PatchMethod
    val path: List<PathSegment>
    val value: Any?
}

/** Как операция меняет документ. */
enum class PatchMethod {
    /** Заменить или создать значение по пути целиком; `{id}` в конце пути, которого нет, — добавить в конец массива. */
    PUT,

    /** Неглубокое слияние: поля значения заменяют поля объекта по пути (вложенные — целиком, null — как значение). */
    PATCH,

    /** Удалить поле объекта или элемент массива по id. */
    DELETE,

    /** Ничего не меняет: данные для журнала и логов. */
    INFO,
}

/** Сегмент пути: ключ объекта или элемент массива с этим id (адрес не зависит от индекса). */
sealed interface PathSegment {

    /** Ключ объекта; в JSON — строка. */
    data class Key(val name: String) : PathSegment

    /** Элемент массива, у которого `id` равен [id]; в JSON — `{"id": "…"}`. */
    data class Id(val id: String) : PathSegment
}

/** Построители путей: строка — ключ объекта, [id] — элемент массива по id. */
object PatchPaths {

    /** Путь: строка — ключ объекта, [PathSegment] — как есть (например, [id]). */
    fun path(vararg segments: Any): List<PathSegment> = segments.map {
        when (it) {
            is String -> PathSegment.Key(it)
            is PathSegment -> it
            else -> throw IllegalArgumentException("Path segment must be a String or a PathSegment, got ${it::class.qualifiedName}")
        }
    }

    /** Элемент массива по id. */
    fun id(id: UUID): PathSegment = PathSegment.Id(id.toString())

    fun id(id: String): PathSegment = PathSegment.Id(id)
}
