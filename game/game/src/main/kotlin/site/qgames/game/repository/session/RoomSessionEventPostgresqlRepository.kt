package site.qgames.game.repository.session

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import site.qgames.game.entity.session.RoomSessionEventEntity
import site.qgames.game.util.getInstant
import site.qgames.game.util.getUuid
import site.qgames.game.util.toTimestamp
import java.util.UUID

/** Таблица room_session_event (журнал партии). Используется только из [RoomSessionEventRepository]. */
@Repository
class RoomSessionEventPostgresqlRepository(private val jdbcClient: JdbcClient) {

    fun insert(event: RoomSessionEventEntity) {
        jdbcClient.sql(
            """
            INSERT INTO "game#room_session_event" (session_id, seq, method, code, data, created_at)
            VALUES (:sessionId, :seq, :method, :code, CAST(:data AS jsonb), :createdAt)
            """,
        )
            .param("sessionId", event.sessionId)
            .param("seq", event.seq)
            .param("method", event.method)
            .param("code", event.code)
            .param("data", event.data)
            .param("createdAt", event.createdAt.toTimestamp())
            .update()
    }

    fun findRange(sessionId: UUID, fromSeq: Long, toSeq: Long): List<RoomSessionEventEntity> =
        jdbcClient.sql(
            """
            SELECT session_id, seq, method, code, data::text AS data, created_at
            FROM "game#room_session_event"
            WHERE session_id = :sessionId AND seq BETWEEN :fromSeq AND :toSeq
            ORDER BY seq
            """,
        )
            .param("sessionId", sessionId)
            .param("fromSeq", fromSeq)
            .param("toSeq", toSeq)
            .query(mapper)
            .list()

    private val mapper = RowMapper { rs, _ ->
        RoomSessionEventEntity(
            sessionId = rs.getUuid("session_id")!!,
            seq = rs.getLong("seq"),
            method = rs.getString("method"),
            code = rs.getString("code"),
            data = rs.getString("data"),
            createdAt = rs.getInstant("created_at")!!,
        )
    }
}
