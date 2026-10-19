package site.qgames.game.model.session

import java.time.Instant
import java.util.UUID

/** Принятый ход партии. [seq] совпадает с версией партии после хода. */
data class RoomSessionMoveModel(
    val sessionId: UUID,
    val seq: Long,
    val playerId: UUID,
    /** JSON хода, формат задаёт модуль игры. */
    val move: String,
    val createdAt: Instant,
)
