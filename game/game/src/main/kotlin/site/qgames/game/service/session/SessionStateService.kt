package site.qgames.game.service.session

import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import site.qgames.game.cache.session.SessionStateCache
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.SessionStateModel
import site.qgames.game.repository.session.RoomSessionEventRepository
import site.qgames.game.service.game.GameRegistry
import site.qgames.games.core.SessionEngine
import site.qgames.common.docpatch.DocumentPatch
import site.qgames.games.core.ViewerKey

/**
 * Сборка документа партии для зрителя из журнала. Берёт из [SessionStateCache] последний документ проекции (seq K),
 * дочитывает события K+1…N, применяет к ним `DocumentPatch.apply(mask(e, viewer))` и кладёт результат (N) обратно.
 * В кеше ничего нет — свёртка с нуля. Полный документ (проверка ходов) — проекция [ViewerKey.Full].
 */
@Service
class SessionStateService(
    private val cache: SessionStateCache,
    private val roomSessionEventRepository: RoomSessionEventRepository,
    private val gameRegistry: GameRegistry,
    private val sessionEngine: SessionEngine,
) {

    /**
     * Документ партии [session] для [viewer] на seq [RoomSessionModel.lastSeq]. Если в кеше уже есть документ новее
     * (его собрал другой запрос по более свежему журналу), возвращается он: в кеш попадают только закоммиченные
     * события.
     */
    fun state(session: RoomSessionModel, viewer: ViewerKey): SessionStateModel {
        val target = session.lastSeq
        val cached = cache.get(session.id, viewer)
        if (cached != null && cached.seq >= target) {
            return cached
        }
        val rules = gameRegistry.rules(session.gameCode)
        val events = roomSessionEventRepository.findRange(session.id, (cached?.seq ?: 0) + 1, target)
        val folded = events.fold(cached?.document) { document, event ->
            DocumentPatch.apply(document, sessionEngine.mask(rules, event.event, viewer))
        } ?: error("Session ${session.id} has an empty journal")
        return SessionStateModel(target, folded).also { cache.put(session.id, viewer, it) }
    }

    /** Кладёт документ в кеш после коммита текущей транзакции: при откате его события не существуют. */
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
