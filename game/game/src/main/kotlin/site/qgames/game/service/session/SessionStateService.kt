package site.qgames.game.service.session

import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import site.qgames.game.cache.session.SessionStateCache
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.SessionStateModel
import site.qgames.game.repository.session.RoomSessionEventRepository
import site.qgames.game.service.game.GameRegistry
import site.qgames.games.api.session.SessionEngine
import site.qgames.games.api.session.ViewerKey
import java.util.UUID

/**
 * Сборка состояния партии для зрителя из журнала. Берёт из [SessionStateCache] последнее состояние проекции
 * (seq K), дочитывает события K+1…N, применяет к ним `evolve(mask(e, viewer))` и кладёт результат (N) обратно.
 * В кеше ничего нет — свёртка с нуля. Полное состояние (проверка ходов) — проекция [ViewerKey.Full].
 */
@Service
class SessionStateService(
    private val cache: SessionStateCache,
    private val roomSessionEventRepository: RoomSessionEventRepository,
    private val gameRegistry: GameRegistry,
) {

    /**
     * Состояние партии [session] для [viewer] на seq [RoomSessionModel.lastSeq]. Если в кеше уже есть состояние новее
     * (его собрал другой запрос по более свежему журналу), возвращается оно: в кеш попадают только закоммиченные
     * события.
     */
    fun state(session: RoomSessionModel, viewer: ViewerKey): SessionStateModel {
        val target = session.lastSeq
        val cached = cache.get(session.id, viewer)
        if (cached != null && cached.seq >= target) {
            return cached
        }
        val rules = gameRegistry.rules(session.gameCode)
        val events = roomSessionEventRepository.findRange(session.id, session.gameCode, (cached?.seq ?: 0) + 1, target)
        val folded = events.fold(cached?.session) { state, event ->
            SessionEngine.evolve(rules, state, SessionEngine.mask(rules, event.event, viewer))
        } ?: error("Session ${session.id} has an empty journal")
        return SessionStateModel(target, folded).also { cache.put(session.id, viewer, it) }
    }

    /** Кладёт состояние в кеш после коммита текущей транзакции: при откате его события не существуют. */
    fun cacheAfterCommit(sessionId: UUID, viewer: ViewerKey, state: SessionStateModel) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = cache.put(sessionId, viewer, state)
            },
        )
    }
}
