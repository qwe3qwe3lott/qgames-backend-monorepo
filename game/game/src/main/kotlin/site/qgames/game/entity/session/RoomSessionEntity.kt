package site.qgames.game.entity.session

import java.time.Instant
import java.util.UUID

/** Строка таблицы room_session. */
data class RoomSessionEntity(
    val id: UUID,
    val roomId: UUID,
    val gameType: String,
    val status: String,
    /** JSON (jsonb) состояния. */
    val state: String,
    val stateSchemaVersion: Int,
    val seed: Long,
    val version: Long,
    val createdAt: Instant,
    val finishedAt: Instant?,
)
