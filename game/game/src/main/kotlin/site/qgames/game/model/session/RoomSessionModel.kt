package site.qgames.game.model.session

import site.qgames.game.model.game.GameCode
import java.time.Instant
import java.util.UUID

/** Партия комнаты. Состояния здесь нет: оно собирается из журнала (SessionStateService). */
data class RoomSessionModel(
    val id: UUID,
    val roomId: UUID,
    val gameCode: GameCode,
    val status: RoomSessionStatus,
    val seed: Long,
    /** seq последнего события журнала. */
    val lastSeq: Long,
    /** Снимок JSON advanced-настроек комнаты на момент старта (GameRules.advancedClass). */
    val settings: String,
    /** Проекция таймера: дедлайн идущего таймера хода; null — без таймера, на паузе или партия не идёт. */
    val moveDeadline: Instant?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

enum class RoomSessionStatus {
    IN_PROGRESS,
    FINISHED,
    CANCELLED,
}
