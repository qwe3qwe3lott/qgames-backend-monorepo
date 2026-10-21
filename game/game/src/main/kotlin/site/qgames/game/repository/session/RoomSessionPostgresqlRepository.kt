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
        stateSchemaVersion: Int,
        seed: Long,
        timeControlMode: String,
        moveSeconds: Int?,
        gameSettings: String,
        players: List<UUID>,
        now: Instant,
    ): RoomSessionEntity {
        val session = jdbcClient.sql(
            """
            INSERT INTO room_session (room_id, game_code, status, state_schema_version, seed, time_control_mode,
                                      move_seconds, game_settings, created_at)
            VALUES (:roomId, :gameCode, :status, :stateSchemaVersion, :seed, :timeControlMode,
                    :moveSeconds, CAST(:gameSettings AS jsonb), :now)
            RETURNING $COLUMNS
            """,
        )
            .param("roomId", roomId)
            .param("gameCode", gameCode)
            .param("status", status)
            .param("stateSchemaVersion", stateSchemaVersion)
            .param("seed", seed)
            .param("timeControlMode", timeControlMode)
            .param("moveSeconds", moveSeconds)
            .param("gameSettings", gameSettings)
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
     * бы пачку. Статус — литералом, чтобы подходил частичный индекс room_session_move_deadline_idx. Таймер на паузе
     * дедлайна в проекции не имеет.
     */
    fun findIdsWithExpiredMove(now: Instant, limit: Int): List<UUID> =
        jdbcClient.sql(
            """
            SELECT s.id
            FROM room_session s
                     JOIN game g ON g.code = s.game_code
            WHERE s.status = 'IN_PROGRESS' AND s.move_deadline <= :now
              AND g.publish_status <> 'FORCE_DISABLED'
            ORDER BY s.move_deadline
            LIMIT :limit
            """,
        )
            .param("now", now.toTimestamp())
            .param("limit", limit)
            .query { rs, _ -> rs.getUuid("id")!! }
            .list()

    /** После записи событий журнала: последний seq, статус и проекция таймера. */
    fun advance(id: UUID, lastSeq: Long, status: String, moveDeadline: Instant?, finishedAt: Instant?) {
        jdbcClient.sql(
            """
            UPDATE room_session
            SET last_seq = :lastSeq, status = :status, move_deadline = :moveDeadline, finished_at = :finishedAt
            WHERE id = :id
            """,
        )
            .param("id", id)
            .param("lastSeq", lastSeq)
            .param("status", status)
            .param("moveDeadline", moveDeadline.toTimestamp())
            .param("finishedAt", finishedAt.toTimestamp())
            .update()
    }

    private val mapper = RowMapper { rs, _ ->
        RoomSessionEntity(
            id = rs.getUuid("id")!!,
            roomId = rs.getUuid("room_id")!!,
            gameCode = rs.getString("game_code"),
            status = rs.getString("status"),
            stateSchemaVersion = rs.getInt("state_schema_version"),
            seed = rs.getLong("seed"),
            lastSeq = rs.getLong("last_seq"),
            timeControlMode = rs.getString("time_control_mode"),
            moveSeconds = rs.getIntOrNull("move_seconds"),
            gameSettings = rs.getString("game_settings"),
            moveDeadline = rs.getInstant("move_deadline"),
            createdAt = rs.getInstant("created_at")!!,
            finishedAt = rs.getInstant("finished_at"),
        )
    }

    companion object {
        private const val COLUMNS =
            "id, room_id, game_code, status, state_schema_version, seed, last_seq, time_control_mode, " +
                "move_seconds, game_settings::text AS game_settings, move_deadline, created_at, finished_at"
    }
}
