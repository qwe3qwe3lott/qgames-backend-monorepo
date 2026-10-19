package site.qgames.game.entity.session

import java.time.Instant
import java.util.UUID

/** Строка таблицы room_session_move (журнал ходов). */
data class RoomSessionMoveEntity(
    val sessionId: UUID,
    val seq: Long,
    val playerId: UUID,
    /** JSON (jsonb) хода. */
    val move: String,
    val createdAt: Instant,
)
