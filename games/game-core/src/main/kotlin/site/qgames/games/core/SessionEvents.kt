package site.qgames.games.core

import site.qgames.common.docpatch.PatchMethod
import site.qgames.common.docpatch.PatchPaths
import site.qgames.common.docpatch.PathSegment
import java.util.UUID

/**
 * Построители событий ([SessionEvent]) и коды событий платформы. Модули и игры только порождают события через
 * [put], [patch], [delete] и [info]; применяет их один общий DocumentPatch. Значение может быть любым объектом —
 * [SessionEngine] переводит его в JSON-дерево.
 *
 * Пути собирает [path]: строка — ключ объекта, [id] — элемент массива по id. Пути состояния начинаются с [STATE].
 */
object SessionEvents {

    /** Платформа: первое событие журнала, `PUT [] {status, meta, state}`. */
    const val SESSION_STARTED = "session-started"

    /** Платформа: партия закончилась по правилам, `PATCH [] {status: FINISHED}`. Последнее событие партии. */
    const val SESSION_FINISHED = "session-finished"

    /** Платформа: партию отменил владелец, `PATCH [] {status: CANCELLED}`. Последнее событие партии. */
    const val SESSION_CANCELLED = "session-cancelled"

    /** Коды событий, после которых партия закончена. */
    val ENDING: Set<String> = setOf(SESSION_FINISHED, SESSION_CANCELLED)

    /** Ключ состояния в документе партии. */
    const val STATE = "state"

    fun put(code: String, path: List<PathSegment>, value: Any?) = SessionEvent(code, PatchMethod.PUT, path, value)

    /** [value] — объект: поля сливаются с объектом по пути на один уровень. */
    fun patch(code: String, path: List<PathSegment>, value: Map<String, Any?>) =
        SessionEvent(code, PatchMethod.PATCH, path, value)

    fun delete(code: String, path: List<PathSegment>) = SessionEvent(code, PatchMethod.DELETE, path, null)

    /** Событие для журнала, документ не меняет. [data] — объект. */
    fun info(code: String, data: Map<String, Any?>) = SessionEvent(code, PatchMethod.INFO, emptyList(), data)

    /** Путь: строка — ключ объекта, [PathSegment] — как есть (например, [id]). */
    fun path(vararg segments: Any): List<PathSegment> = PatchPaths.path(*segments)

    /** Элемент массива по id. */
    fun id(id: UUID): PathSegment = PatchPaths.id(id)

    fun id(id: String): PathSegment = PatchPaths.id(id)

    fun finished() = patch(SESSION_FINISHED, emptyList(), mapOf("status" to SessionStatus.FINISHED))

    fun cancelled() = patch(SESSION_CANCELLED, emptyList(), mapOf("status" to SessionStatus.CANCELLED))
}
