package site.qgames.game.model.session

import site.qgames.game.model.room.RoomGameType
import java.time.Instant
import java.util.UUID

data class RoomSessionModel(
    val id: UUID,
    val roomId: UUID,
    val gameType: RoomGameType,
    val status: RoomSessionStatus,
    /** JSON состояния, формат задаёт модуль игры. */
    val state: String,
    val stateSchemaVersion: Int,
    val seed: Long,
    val version: Long,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

enum class RoomSessionStatus {
    IN_PROGRESS,
    FINISHED,
    CANCELLED,
}
