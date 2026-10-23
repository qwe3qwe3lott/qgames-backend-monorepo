package site.qgames.game.cache.session

import site.qgames.game.model.session.SessionStateModel
import site.qgames.games.api.session.ViewerKey
import java.util.UUID

/**
 * Кеш состояний партии по проекции зрителя: `(sessionId, viewerKey) → (seq, state)`. Хранит последнее собранное
 * состояние; по нему SessionStateService доигрывает только новые события журнала.
 *
 * Инвалидация не нужна, в том числе между инстансами: журнал только дописывается, состояние на seq K неизменно.
 * Класть можно только состояния из закоммиченных событий. Реализация — в памяти инстанса (Caffeine).
 * Ключ — проекция, а не подключение: одно состояние годится всем зрителям с тем же ключом.
 */
interface SessionStateCache {

    fun get(sessionId: UUID, viewer: ViewerKey): SessionStateModel?

    /** Кладёт состояние, если оно не старше уже закешированного. */
    fun put(sessionId: UUID, viewer: ViewerKey, state: SessionStateModel)
}
