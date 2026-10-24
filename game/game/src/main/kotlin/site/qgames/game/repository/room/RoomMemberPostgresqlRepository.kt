package site.qgames.game.repository.room

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import site.qgames.game.entity.room.RoomMemberEntity
import site.qgames.game.util.getInstant
import site.qgames.game.util.getUuid
import site.qgames.game.util.toTimestamp
import java.time.Instant
import java.util.UUID

/** Таблица room_member. Используется только из [RoomMemberRepository]. */
@Repository
class RoomMemberPostgresqlRepository(private val jdbcClient: JdbcClient) {

    fun findAll(roomId: UUID): List<RoomMemberEntity> =
        jdbcClient.sql("SELECT * FROM \"game#room_member\" WHERE room_id = :roomId ORDER BY joined_at, user_id")
            .param("roomId", roomId)
            .query(mapper)
            .list()

    fun insert(member: RoomMemberEntity) {
        jdbcClient.sql(
            """
            INSERT INTO "game#room_member" (room_id, user_id, role, joined_at, left_at)
            VALUES (:roomId, :userId, :role, :joinedAt, :leftAt)
            """,
        )
            .param("roomId", member.roomId)
            .param("userId", member.userId)
            .param("role", member.role)
            .param("joinedAt", member.joinedAt.toTimestamp())
            .param("leftAt", member.leftAt.toTimestamp())
            .update()
    }

    fun updateRole(roomId: UUID, userId: UUID, role: String) {
        jdbcClient.sql("UPDATE \"game#room_member\" SET role = :role WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", userId)
            .param("role", role)
            .update()
    }

    fun updateLeftAt(roomId: UUID, userId: UUID, leftAt: Instant?) {
        jdbcClient.sql("UPDATE \"game#room_member\" SET left_at = :leftAt WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", userId)
            .param("leftAt", leftAt.toTimestamp())
            .update()
    }

    fun findRoomIdsByUser(userId: UUID): List<UUID> =
        jdbcClient.sql("SELECT room_id FROM \"game#room_member\" WHERE user_id = :userId")
            .param("userId", userId)
            .query { rs, _ -> rs.getUuid("room_id")!! }
            .list()

    fun delete(roomId: UUID, userId: UUID) {
        jdbcClient.sql("DELETE FROM \"game#room_member\" WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", userId)
            .update()
    }

    fun deleteAbsent(roomId: UUID): List<UUID> =
        jdbcClient.sql("DELETE FROM \"game#room_member\" WHERE room_id = :roomId AND left_at IS NOT NULL RETURNING user_id")
            .param("roomId", roomId)
            .query { rs, _ -> rs.getUuid("user_id")!! }
            .list()

    private val mapper = RowMapper { rs, _ ->
        RoomMemberEntity(
            roomId = rs.getUuid("room_id")!!,
            userId = rs.getUuid("user_id")!!,
            role = rs.getString("role"),
            joinedAt = rs.getInstant("joined_at")!!,
            leftAt = rs.getInstant("left_at"),
        )
    }
}
