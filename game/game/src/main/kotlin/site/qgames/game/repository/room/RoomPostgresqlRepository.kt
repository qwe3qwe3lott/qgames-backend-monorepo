package site.qgames.game.repository.room

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import site.qgames.game.entity.room.RoomEntity
import site.qgames.game.util.getInstant
import site.qgames.game.util.getUuid
import site.qgames.game.util.toTimestamp
import java.time.Instant
import java.util.UUID

/** Таблица room. Используется только из [RoomRepository]. */
@Repository
class RoomPostgresqlRepository(private val jdbcClient: JdbcClient) {

    fun insert(gameType: String, ownerId: UUID, status: String, now: Instant): RoomEntity =
        jdbcClient.sql(
            """
            INSERT INTO room (game_type, owner_id, status, created_at)
            VALUES (:gameType, :ownerId, :status, :now)
            RETURNING *
            """,
        )
            .param("gameType", gameType)
            .param("ownerId", ownerId)
            .param("status", status)
            .param("now", now.toTimestamp())
            .query(mapper)
            .single()

    fun findById(id: UUID): RoomEntity? =
        jdbcClient.sql("SELECT * FROM room WHERE id = :id").param("id", id).query(mapper).optional().orElse(null)

    fun lockById(id: UUID): RoomEntity? =
        jdbcClient.sql("SELECT * FROM room WHERE id = :id FOR UPDATE").param("id", id).query(mapper).optional().orElse(null)

    fun update(room: RoomEntity) {
        jdbcClient.sql(
            """
            UPDATE room
            SET owner_id = :ownerId, status = :status, current_session_id = :currentSessionId, empty_since = :emptySince
            WHERE id = :id
            """,
        )
            .param("id", room.id)
            .param("ownerId", room.ownerId)
            .param("status", room.status)
            .param("currentSessionId", room.currentSessionId)
            .param("emptySince", room.emptySince.toTimestamp())
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
            createdAt = rs.getInstant("created_at")!!,
        )
    }
}
