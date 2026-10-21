package site.qgames.game.stream.common

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Открытые на инстансе потоки по ключу (комната, партия). Добавление и удаление — атомарные операции над ключом:
 * иначе параллельное удаление последнего подписчика могло бы выкинуть множество, и новый подписчик остался бы в
 * «осиротевшем» множестве без событий.
 */
class StreamIndex<S : Any>(private val keyOf: (S) -> UUID) {

    private val byKey = ConcurrentHashMap<UUID, MutableSet<S>>()

    fun add(subscriber: S) {
        byKey.compute(keyOf(subscriber)) { _, set -> (set ?: ConcurrentHashMap.newKeySet()).apply { add(subscriber) } }
    }

    fun remove(subscriber: S) {
        byKey.computeIfPresent(keyOf(subscriber)) { _, set -> set.apply { remove(subscriber) }.takeIf { it.isNotEmpty() } }
    }

    operator fun get(key: UUID): Collection<S> = byKey[key].orEmpty()

    fun all(): List<S> = byKey.values.flatten()
}
