package site.qgames.game.repository.session

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import site.qgames.game.entity.session.RoomSessionEntity
import site.qgames.game.util.getInstant
import site.qgames.game.util.getIntOrNull
import site.qgames.game.util.getUuid
import site.qgames.game.util.toTimestamp
import java.time.Instant
import java.util.UUID

/** Таблицы room_session и room_session_player. Используется только из [RoomSessionRepository]. */
@Repository
class RoomSessionPostgresqlRepository(private val jdbcClient: JdbcClient) {

    fun insert(
        roomId: UUID,
        gameCode: String,
        status: String,
        state: String,
        stateSchemaVersion: Int,
        seed: Long,
        timeControlMode: String,
        moveSeconds: Int?,
        gameSettings: String,
        moveDeadline: Instant?,
        players: List<UUID>,
        now: Instant,
    ): RoomSessionEntity {
        val session = jdbcClient.sql(
            """
            INSERT INTO room_session (room_id, game_code, status, state, state_schema_version, seed, time_control_mode,
                                      move_seconds, game_settings, move_deadline, created_at)
            VALUES (:roomId, :gameCode, :status, CAST(:state AS jsonb), :stateSchemaVersion, :seed, :timeControlMode,
                    :moveSeconds, CAST(:gameSettings AS jsonb), :moveDeadline, :now)
            RETURNING $COLUMNS
            """,
        )
            .param("roomId", roomId)
            .param("gameCode", gameCode)
            .param("status", status)
            .param("state", state)
            .param("stateSchemaVersion", stateSchemaVersion)
            .param("seed", seed)
            .param("timeControlMode", timeControlMode)
            .param("moveSeconds", moveSeconds)
            .param("gameSettings", gameSettings)
            .param("moveDeadline", moveDeadline.toTimestamp())
            .param("now", now.toTimestamp())
            .query(mapper)
            .single()
        players.forEachIndexed { seat, userId ->
            jdbcClient.sql("INSERT INTO room_session_player (session_id, seat, user_id) VALUES (:sessionId, :seat, :userId)")
                .param("sessionId", session.id)
                .param("seat", seat)
                .param("userId", userId)
                .update()
        }
        return session
    }

    fun findById(id: UUID): RoomSessionEntity? =
        jdbcClient.sql("SELECT $COLUMNS FROM room_session WHERE id = :id").param("id", id).query(mapper).optional().orElse(null)

    fun findLatest(roomId: UUID): RoomSessionEntity? =
        jdbcClient.sql("SELECT $COLUMNS FROM room_session WHERE room_id = :roomId ORDER BY created_at DESC, id DESC LIMIT 1")
            .param("roomId", roomId)
            .query(mapper)
            .optional()
            .orElse(null)

    fun findPlayers(sessionId: UUID): List<UUID> =
        jdbcClient.sql("SELECT user_id FROM room_session_player WHERE session_id = :sessionId ORDER BY seat")
            .param("sessionId", sessionId)
            .query { rs, _ -> rs.getUuid("user_id")!! }
            .list()

    /**
     * Партии принудительно отключённых игр пропускаются: их отменит ForceDisabledGameCleanupJob, а иначе они занимали
     * бы пачку. Статус — литералом, чтобы подходил частичный индекс room_session_move_deadline_idx.
     */
    fun findIdsWithExpiredMove(now: Instant, limit: Int): List<UUID> =
        jdbcClient.sql(
            """
            SELECT s.id
            FROM room_session s
                     JOIN game g ON g.code = s.game_code
            WHERE s.status = 'IN_PROGRESS' AND s.move_deadline <= :now AND s.paused_at IS NULL
              AND g.publish_status <> 'FORCE_DISABLED'
            ORDER BY s.move_deadline
            LIMIT :limit
            """,
        )
            .param("now", now.toTimestamp())
            .param("limit", limit)
            .query { rs, _ -> rs.getUuid("id")!! }
            .list()

    fun updateState(
        id: UUID,
        expectedVersion: Long,
        state: String,
        status: String,
        moveDeadline: Instant?,
        finishedAt: Instant?,
    ): Boolean =
        jdbcClient.sql(
            """
            UPDATE room_session
            SET state = CAST(:state AS jsonb), status = :status, move_deadline = :moveDeadline, finished_at = :finishedAt,
                version = version + 1
            WHERE id = :id AND version = :expectedVersion
            """,
        )
            .param("id", id)
            .param("expectedVersion", expectedVersion)
            .param("state", state)
            .param("status", status)
            .param("moveDeadline", moveDeadline.toTimestamp())
            .param("finishedAt", finishedAt.toTimestamp())
            .update() == 1

    fun clearMoveDeadline(id: UUID) {
        jdbcClient.sql("UPDATE room_session SET move_deadline = NULL WHERE id = :id").param("id", id).update()
    }

    fun pause(id: UUID, now: Instant) {
        jdbcClient.sql("UPDATE room_session SET paused_at = :now WHERE id = :id AND paused_at IS NULL")
            .param("id", id)
            .param("now", now.toTimestamp())
            .update()
    }

    /** Снимает паузу и сдвигает дедлайн хода (если он есть) на [micros] микросекунд. */
    fun resume(id: UUID, micros: Long) {
        jdbcClient.sql(
            """
            UPDATE room_session
            SET move_deadline = move_deadline + :micros * interval '1 microsecond', paused_at = NULL
            WHERE id = :id
            """,
        )
            .param("id", id)
            .param("micros", micros)
            .update()
    }

    fun finish(id: UUID, status: String, finishedAt: Instant) {
        jdbcClient.sql(
            """
            UPDATE room_session
            SET status = :status, finished_at = :finishedAt, move_deadline = NULL, paused_at = NULL
            WHERE id = :id
            """,
        )
            .param("id", id)
            .param("status", status)
            .param("finishedAt", finishedAt.toTimestamp())
            .update()
    }

    private val mapper = RowMapper { rs, _ ->
        RoomSessionEntity(
            id = rs.getUuid("id")!!,
            roomId = rs.getUuid("room_id")!!,
            gameCode = rs.getString("game_code"),
            status = rs.getString("status"),
            state = rs.getString("state"),
            stateSchemaVersion = rs.getInt("state_schema_version"),
            seed = rs.getLong("seed"),
            version = rs.getLong("version"),
            timeControlMode = rs.getString("time_control_mode"),
            moveSeconds = rs.getIntOrNull("move_seconds"),
            gameSettings = rs.getString("game_settings"),
            moveDeadline = rs.getInstant("move_deadline"),
            pausedAt = rs.getInstant("paused_at"),
            createdAt = rs.getInstant("created_at")!!,
            finishedAt = rs.getInstant("finished_at"),
        )
    }

    companion object {
        private const val COLUMNS =
            "id, room_id, game_code, status, state::text AS state, state_schema_version, seed, version, time_control_mode, " +
                "move_seconds, game_settings::text AS game_settings, move_deadline, paused_at, created_at, finished_at"
    }
}
