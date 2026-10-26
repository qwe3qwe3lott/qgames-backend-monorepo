package site.qgames.game.model.session

import java.time.Instant
import java.util.UUID
import site.qgames.games.core.SessionEvent

/** Событие журнала партии: seq, момент записи и событие с полными (немаскированными) данными. */
data class RoomSessionEventModel(
    val seq: Long,
    val createdAt: Instant,
    val event: SessionEvent,
)

/**
 * Уведомление (Postgres NOTIFY, канал session_events): в журнал партии [sessionId] дописаны события
 * [fromSeq]..[toSeq]. Сами события диспетчер читает из журнала.
 */
data class SessionEventsNotificationModel(
    val sessionId: UUID,
    val fromSeq: Long,
    val toSeq: Long,
)
