package site.qgames.game.repository.room

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import site.qgames.game.util.getUuid
import site.qgames.game.util.toTimestamp
import java.time.Instant
import java.util.UUID

/** Таблица room_connection. Используется только из [RoomConnectionRepository]. */
@Repository
class RoomConnectionPostgresqlRepository(private val jdbcClient: JdbcClient) {

    fun insert(roomId: UUID, userId: UUID, instanceId: UUID, expiresAt: Instant): UUID =
        jdbcClient.sql(
            """
            INSERT INTO room_connection (room_id, user_id, instance_id, expires_at)
            VALUES (:roomId, :userId, :instanceId, :expiresAt)
            RETURNING id
            """,
        )
            .param("roomId", roomId)
            .param("userId", userId)
            .param("instanceId", instanceId)
            .param("expiresAt", expiresAt.toTimestamp())
            .query { rs, _ -> rs.getUuid("id")!! }
            .single()

    fun delete(id: UUID): Boolean =
        jdbcClient.sql("DELETE FROM room_connection WHERE id = :id").param("id", id).update() > 0

    fun existsForUser(roomId: UUID, userId: UUID): Boolean =
        jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM room_connection WHERE room_id = :roomId AND user_id = :userId)")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(Boolean::class.java)
            .single()

    fun renew(ids: Collection<UUID>, expiresAt: Instant): List<UUID> =
        jdbcClient.sql("UPDATE room_connection SET expires_at = :expiresAt WHERE id IN (:ids) RETURNING id")
            .param("ids", ids)
            .param("expiresAt", expiresAt.toTimestamp())
            .query { rs, _ -> rs.getUuid("id")!! }
            .list()

    fun findRoomIdsWithExpired(now: Instant, limit: Int): List<UUID> =
        jdbcClient.sql(
            """
            SELECT room_id
            FROM room_connection
            WHERE expires_at <= :now
            GROUP BY room_id
            ORDER BY min(expires_at)
            LIMIT :limit
            """,
        )
            .param("now", now.toTimestamp())
            .param("limit", limit)
            .query { rs, _ -> rs.getUuid("room_id")!! }
            .list()

    fun deleteExpired(roomId: UUID, now: Instant): List<UUID> =
        jdbcClient.sql("DELETE FROM room_connection WHERE room_id = :roomId AND expires_at <= :now RETURNING user_id")
            .param("roomId", roomId)
            .param("now", now.toTimestamp())
            .query { rs, _ -> rs.getUuid("user_id")!! }
            .list()
}
