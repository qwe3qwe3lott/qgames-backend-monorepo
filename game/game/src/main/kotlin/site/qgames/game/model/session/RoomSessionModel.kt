package site.qgames.game.model.session

import site.qgames.game.model.room.RoomGameType
import site.qgames.game.model.room.RoomTimeControlModel
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
    /** Снимок правил времени комнаты на момент старта. */
    val timeControl: RoomTimeControlModel,
    /** Снимок JSON настроек игры на момент старта. */
    val gameSettings: String,
    /** Когда истекает время текущего хода; null — без таймера или партия не идёт. */
    val moveDeadline: Instant?,
    /** С какого момента партия на паузе; null — не на паузе. */
    val pausedAt: Instant?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

enum class RoomSessionStatus {
    IN_PROGRESS,
    FINISHED,
    CANCELLED,
}
