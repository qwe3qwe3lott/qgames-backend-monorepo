package site.qgames.game.model.session

import java.time.Instant
import site.qgames.games.core.ViewerKey

/**
 * Документ партии `{status, meta, state}` для одного зрителя после события [seq]: свёртка его маскированных событий
 * 1..[seq] (JSON-дерево, как его видит клиент).
 */
data class SessionStateModel(
    val seq: Long,
    val document: Map<String, Any?>,
)

/** Кто подписался на поток партии и какую проекцию ему собирать. */
data class SessionSubscriptionModel(
    val session: RoomSessionModel,
    val viewer: ViewerKey,
)

/** Снапшот потока партии: документ зрителя на seq и время сервера. */
data class SessionSnapshotModel(
    val state: SessionStateModel,
    val serverTime: Instant,
)

/** События журнала, дописанные к партии игры [session], для раздачи подписчикам. */
data class SessionEventsBatchModel(
    val session: RoomSessionModel,
    val events: List<RoomSessionEventModel>,
)
