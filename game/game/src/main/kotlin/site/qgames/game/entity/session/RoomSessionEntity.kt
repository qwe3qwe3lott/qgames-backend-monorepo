package site.qgames.game.entity.session

import java.time.Instant
import java.util.UUID

/** Строка таблицы room_session. */
data class RoomSessionEntity(
    val id: UUID,
    val roomId: UUID,
    val gameCode: String,
    val status: String,
    val stateSchemaVersion: Int,
    val seed: Long,
    val lastSeq: Long,
    val timeControlMode: String,
    val moveSeconds: Int?,
    /** JSON (jsonb) настроек игры. */
    val gameSettings: String,
    val moveDeadline: Instant?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

/** Строка таблицы room_session_event (журнал партии). */
data class RoomSessionEventEntity(
    val sessionId: UUID,
    val seq: Long,
    val method: String,
    val code: String,
    /** JSON (jsonb) данных события: PUT и PATCH — {path, value}, DELETE — {path}, INFO — объект. */
    val data: String,
    val createdAt: Instant,
)
