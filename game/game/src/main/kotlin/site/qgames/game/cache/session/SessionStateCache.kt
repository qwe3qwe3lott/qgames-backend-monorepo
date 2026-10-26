package site.qgames.game.cache.session

import java.util.UUID
import site.qgames.game.model.session.SessionStateModel
import site.qgames.games.core.ViewerKey

/**
 * Кеш состояний партии по проекции зрителя: `(sessionId, viewerKey) → (seq, state)`. Хранит последнее собранное
 * состояние; по нему SessionStateService доигрывает только новые события журнала.
 *
 * Инвалидация не нужна: журнал только дописывается, состояние на seq K неизменно.
 * Класть можно только состояния из закоммиченных событий. Реализация — в памяти процесса (Caffeine).
 * Ключ — проекция, а не подключение: одно состояние годится всем зрителям с тем же ключом.
 */
interface SessionStateCache {

    fun get(sessionId: UUID, viewer: ViewerKey): SessionStateModel?

    /** Кладёт состояние, если оно не старше уже закешированного. */
    fun put(sessionId: UUID, viewer: ViewerKey, state: SessionStateModel)
}
