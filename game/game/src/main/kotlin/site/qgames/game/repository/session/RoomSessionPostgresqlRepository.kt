package site.qgames.game.repository.session

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import site.qgames.game.entity.session.RoomSessionEntity
import site.qgames.game.util.getInstant
import site.qgames.game.util.getUuid
import site.qgames.game.util.toTimestamp
import java.time.Instant
import java.util.UUID

/** Таблицы room_session и room_session_player. Используется только из [RoomSessionRepository]. */
@Repository
class RoomSessionPostgresqlRepository(private val jdbcClient: JdbcClient) {

    fun insert(
        roomId: UUID,
        gameType: String,
        status: String,
        state: String,
        stateSchemaVersion: Int,
        seed: Long,
        players: List<UUID>,
        now: Instant,
    ): RoomSessionEntity {
        val session = jdbcClient.sql(
            """
            INSERT INTO room_session (room_id, game_type, status, state, state_schema_version, seed, created_at)
            VALUES (:roomId, :gameType, :status, CAST(:state AS jsonb), :stateSchemaVersion, :seed, :now)
            RETURNING id, room_id, game_type, status, state::text AS state, state_schema_version, seed, version,
                      created_at, finished_at
            """,
        )
            .param("roomId", roomId)
            .param("gameType", gameType)
            .param("status", status)
            .param("state", state)
            .param("stateSchemaVersion", stateSchemaVersion)
            .param("seed", seed)
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
        jdbcClient.sql("$SELECT WHERE id = :id").param("id", id).query(mapper).optional().orElse(null)

    fun findLatest(roomId: UUID): RoomSessionEntity? =
        jdbcClient.sql("$SELECT WHERE room_id = :roomId ORDER BY created_at DESC, id DESC LIMIT 1")
            .param("roomId", roomId)
            .query(mapper)
            .optional()
            .orElse(null)

    fun findPlayers(sessionId: UUID): List<UUID> =
        jdbcClient.sql("SELECT user_id FROM room_session_player WHERE session_id = :sessionId ORDER BY seat")
            .param("sessionId", sessionId)
            .query { rs, _ -> rs.getUuid("user_id")!! }
            .list()

    fun updateState(id: UUID, expectedVersion: Long, state: String, status: String, finishedAt: Instant?): Boolean =
        jdbcClient.sql(
            """
            UPDATE room_session
            SET state = CAST(:state AS jsonb), status = :status, finished_at = :finishedAt, version = version + 1
            WHERE id = :id AND version = :expectedVersion
            """,
        )
            .param("id", id)
            .param("expectedVersion", expectedVersion)
            .param("state", state)
            .param("status", status)
            .param("finishedAt", finishedAt.toTimestamp())
            .update() == 1

    fun finish(id: UUID, status: String, finishedAt: Instant) {
        jdbcClient.sql("UPDATE room_session SET status = :status, finished_at = :finishedAt WHERE id = :id")
            .param("id", id)
            .param("status", status)
            .param("finishedAt", finishedAt.toTimestamp())
            .update()
    }

    private val mapper = RowMapper { rs, _ ->
        RoomSessionEntity(
            id = rs.getUuid("id")!!,
            roomId = rs.getUuid("room_id")!!,
            gameType = rs.getString("game_type"),
            status = rs.getString("status"),
            state = rs.getString("state"),
            stateSchemaVersion = rs.getInt("state_schema_version"),
            seed = rs.getLong("seed"),
            version = rs.getLong("version"),
            createdAt = rs.getInstant("created_at")!!,
            finishedAt = rs.getInstant("finished_at"),
        )
    }

    companion object {
        private const val SELECT = """
            SELECT id, room_id, game_type, status, state::text AS state, state_schema_version, seed, version,
                   created_at, finished_at
            FROM room_session
        """
    }
}
