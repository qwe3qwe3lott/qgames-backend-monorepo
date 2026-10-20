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
        jdbcClient.sql("SELECT * FROM room_member WHERE room_id = :roomId ORDER BY joined_at, user_id")
            .param("roomId", roomId)
            .query(mapper)
            .list()

    fun insert(member: RoomMemberEntity) {
        jdbcClient.sql(
            """
            INSERT INTO room_member (room_id, user_id, role, joined_at, leave_deadline)
            VALUES (:roomId, :userId, :role, :joinedAt, :leaveDeadline)
            """,
        )
            .param("roomId", member.roomId)
            .param("userId", member.userId)
            .param("role", member.role)
            .param("joinedAt", member.joinedAt.toTimestamp())
            .param("leaveDeadline", member.leaveDeadline.toTimestamp())
            .update()
    }

    fun updateRole(roomId: UUID, userId: UUID, role: String) {
        jdbcClient.sql("UPDATE room_member SET role = :role WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", userId)
            .param("role", role)
            .update()
    }

    fun updateLeaveDeadline(roomId: UUID, userId: UUID, leaveDeadline: Instant?) {
        jdbcClient.sql("UPDATE room_member SET leave_deadline = :leaveDeadline WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", userId)
            .param("leaveDeadline", leaveDeadline.toTimestamp())
            .update()
    }

    /** Сдвигает сроки выхода участников [userIds] (у кого срок есть) на [micros] микросекунд. */
    fun shiftLeaveDeadlines(roomId: UUID, userIds: Collection<UUID>, micros: Long) {
        if (userIds.isEmpty()) return
        jdbcClient.sql(
            """
            UPDATE room_member SET leave_deadline = leave_deadline + :micros * interval '1 microsecond'
            WHERE room_id = :roomId AND user_id IN (:userIds) AND leave_deadline IS NOT NULL
            """,
        )
            .param("roomId", roomId)
            .param("userIds", userIds)
            .param("micros", micros)
            .update()
    }

    /**
     * Комнаты, где у кого-то истёк срок выхода к [now], кроме игроков партии на паузе. Без блокировок.
     * `leave_deadline <= :now` — под частичный индекс room_member_leave_deadline_idx.
     */
    fun findRoomIdsWithExpiredLeave(now: Instant, limit: Int): List<UUID> =
        jdbcClient.sql(
            """
            SELECT m.room_id
            FROM room_member m
                     JOIN room r ON r.id = m.room_id
                     LEFT JOIN room_session s ON s.id = r.current_session_id AND s.paused_at IS NOT NULL
            WHERE m.leave_deadline <= :now
              AND NOT EXISTS (SELECT 1 FROM room_session_player p WHERE p.session_id = s.id AND p.user_id = m.user_id)
            GROUP BY m.room_id
            ORDER BY min(m.leave_deadline)
            LIMIT :limit
            """,
        )
            .param("now", now.toTimestamp())
            .param("limit", limit)
            .query { rs, _ -> rs.getUuid("room_id")!! }
            .list()

    fun delete(roomId: UUID, userId: UUID) {
        jdbcClient.sql("DELETE FROM room_member WHERE room_id = :roomId AND user_id = :userId")
            .param("roomId", roomId)
            .param("userId", userId)
            .update()
    }

    private val mapper = RowMapper { rs, _ ->
        RoomMemberEntity(
            roomId = rs.getUuid("room_id")!!,
            userId = rs.getUuid("user_id")!!,
            role = rs.getString("role"),
            joinedAt = rs.getInstant("joined_at")!!,
            leaveDeadline = rs.getInstant("leave_deadline"),
        )
    }
}
