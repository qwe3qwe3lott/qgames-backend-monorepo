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
    val timeControlMode: String,
    val moveSeconds: Int?,
    /** JSON (jsonb) настроек игры. */
    val gameSettings: String,
    val moveDeadline: Instant?,
    /** С какого момента партия на паузе; null — не на паузе. */
    val pausedAt: Instant?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)
