package site.qgames.game.model.session

import site.qgames.games.api.session.Session
import site.qgames.games.api.session.ViewerKey
import java.time.Instant

/** Партия для одного зрителя после события [seq]: свёртка его маскированных событий 1..[seq]. */
data class SessionStateModel(
    val seq: Long,
    val session: Session,
)

/** Кто подписался на поток партии и какую проекцию ему собирать. */
data class SessionSubscriptionModel(
    val session: RoomSessionModel,
    val viewer: ViewerKey,
)

/** Снапшот потока партии: состояние зрителя на seq и время сервера. */
data class SessionSnapshotModel(
    val state: SessionStateModel,
    val serverTime: Instant,
)

/** События журнала, дописанные к партии игры [session], для раздачи подписчикам. */
data class SessionEventsBatchModel(
    val session: RoomSessionModel,
    val events: List<RoomSessionEventModel>,
)
