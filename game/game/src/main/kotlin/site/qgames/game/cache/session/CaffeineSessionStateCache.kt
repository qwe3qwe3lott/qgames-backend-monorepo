package site.qgames.game.cache.session

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.UUID
import org.springframework.stereotype.Component
import site.qgames.game.config.GameProperties
import site.qgames.game.model.session.SessionStateModel
import site.qgames.games.core.ViewerKey

/**
 * [SessionStateCache] в памяти процесса: ограничен по числу записей и вытесняет давно не читанные. Потеря записи
 * безопасна — состояние соберётся из журнала заново.
 */
@Component
class CaffeineSessionStateCache(properties: GameProperties) : SessionStateCache {

    private val cache: Cache<Key, SessionStateModel> = Caffeine.newBuilder()
        .maximumSize(properties.session.stateCache.maxSize)
        .expireAfterAccess(properties.session.stateCache.expireAfterAccess)
        .build()

    override fun get(sessionId: UUID, viewer: ViewerKey): SessionStateModel? = cache.getIfPresent(Key(sessionId, viewer.key))

    override fun put(sessionId: UUID, viewer: ViewerKey, state: SessionStateModel) {
        cache.asMap().merge(Key(sessionId, viewer.key), state) { old, new -> if (new.seq >= old.seq) new else old }
    }

    private data class Key(val sessionId: UUID, val viewer: String)
}
