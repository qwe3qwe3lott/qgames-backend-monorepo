package site.qgames.game.repository.room

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import site.qgames.game.entity.room.RoomEntity
import site.qgames.game.util.getInstant
import site.qgames.game.util.getIntOrNull
import site.qgames.game.util.getUuid
import site.qgames.game.util.toTimestamp
import java.time.Instant
import java.util.UUID

/** Таблица room. Используется только из [RoomRepository]. */
@Repository
class RoomPostgresqlRepository(private val jdbcClient: JdbcClient) {

    fun insert(
        gameType: String,
        ownerId: UUID,
        status: String,
        timeControlMode: String,
        moveSeconds: Int?,
        gameSettings: String,
        now: Instant,
    ): RoomEntity =
        jdbcClient.sql(
            """
            INSERT INTO room (game_type, owner_id, status, time_control_mode, move_seconds, game_settings, created_at)
            VALUES (:gameType, :ownerId, :status, :timeControlMode, :moveSeconds, CAST(:gameSettings AS jsonb), :now)
            RETURNING $COLUMNS
            """,
        )
            .param("gameType", gameType)
            .param("ownerId", ownerId)
            .param("status", status)
            .param("timeControlMode", timeControlMode)
            .param("moveSeconds", moveSeconds)
            .param("gameSettings", gameSettings)
            .param("now", now.toTimestamp())
            .query(mapper)
            .single()

    fun findById(id: UUID): RoomEntity? =
        jdbcClient.sql("SELECT $COLUMNS FROM room WHERE id = :id").param("id", id).query(mapper).optional().orElse(null)

    fun lockById(id: UUID): RoomEntity? =
        jdbcClient.sql("SELECT $COLUMNS FROM room WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query(mapper)
            .optional()
            .orElse(null)

    /** Как [lockById], но уже заблокированную другим запросом комнату пропускает (SKIP LOCKED) и возвращает null. */
    fun lockByIdSkipLocked(id: UUID): RoomEntity? =
        jdbcClient.sql("SELECT $COLUMNS FROM room WHERE id = :id FOR UPDATE SKIP LOCKED")
            .param("id", id)
            .query(mapper)
            .optional()
            .orElse(null)

    /**
     * Блокирует комнату, у которой идёт партия [sessionId] (не на паузе) с истёкшим к [now] временем хода. Комнату, уже
     * заблокированную другим запросом (ход, другой инстанс джоба), пропускает: её обработает следующий запуск.
     * Статус — литералом, чтобы подходил частичный индекс room_session_move_deadline_idx.
     */
    fun lockWithExpiredMove(sessionId: UUID, now: Instant): RoomEntity? =
        jdbcClient.sql(
            """
            SELECT r.id, r.game_type, r.owner_id, r.status, r.current_session_id, r.empty_since, r.time_control_mode,
                   r.move_seconds, r.game_settings::text AS game_settings, r.created_at
            FROM room r
                     JOIN room_session s ON s.id = r.current_session_id
            WHERE s.id = :sessionId AND s.status = 'IN_PROGRESS' AND s.paused_at IS NULL AND s.move_deadline <= :now
            FOR UPDATE OF r SKIP LOCKED
            """,
        )
            .param("sessionId", sessionId)
            .param("now", now.toTimestamp())
            .query(mapper)
            .optional()
            .orElse(null)

    fun update(room: RoomEntity) {
        jdbcClient.sql(
            """
            UPDATE room
            SET owner_id = :ownerId, status = :status, current_session_id = :currentSessionId, empty_since = :emptySince,
                time_control_mode = :timeControlMode, move_seconds = :moveSeconds,
                game_settings = CAST(:gameSettings AS jsonb)
            WHERE id = :id
            """,
        )
            .param("id", room.id)
            .param("ownerId", room.ownerId)
            .param("status", room.status)
            .param("currentSessionId", room.currentSessionId)
            .param("emptySince", room.emptySince.toTimestamp())
            .param("timeControlMode", room.timeControlMode)
            .param("moveSeconds", room.moveSeconds)
            .param("gameSettings", room.gameSettings)
            .update()
    }

    fun delete(id: UUID) {
        jdbcClient.sql("DELETE FROM room WHERE id = :id").param("id", id).update()
    }

    fun deleteEmptySince(threshold: Instant): Int =
        jdbcClient.sql("DELETE FROM room WHERE empty_since < :threshold")
            .param("threshold", threshold.toTimestamp())
            .update()

    private val mapper = RowMapper { rs, _ ->
        RoomEntity(
            id = rs.getUuid("id")!!,
            gameType = rs.getString("game_type"),
            ownerId = rs.getUuid("owner_id"),
            status = rs.getString("status"),
            currentSessionId = rs.getUuid("current_session_id"),
            emptySince = rs.getInstant("empty_since"),
            timeControlMode = rs.getString("time_control_mode"),
            moveSeconds = rs.getIntOrNull("move_seconds"),
            gameSettings = rs.getString("game_settings"),
            createdAt = rs.getInstant("created_at")!!,
        )
    }

    companion object {
        private const val COLUMNS =
            "id, game_type, owner_id, status, current_session_id, empty_since, time_control_mode, move_seconds, " +
                "game_settings::text AS game_settings, created_at"
    }
}
