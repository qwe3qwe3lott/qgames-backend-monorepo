package site.qgames.game.repository.session

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import site.qgames.game.entity.session.RoomSessionMoveEntity
import site.qgames.game.util.toTimestamp

/** Таблица room_session_move. Используется только из [RoomSessionMoveRepository]. */
@Repository
class RoomSessionMovePostgresqlRepository(private val jdbcClient: JdbcClient) {

    fun insert(move: RoomSessionMoveEntity) {
        jdbcClient.sql(
            """
            INSERT INTO room_session_move (session_id, seq, player_id, move, created_at)
            VALUES (:sessionId, :seq, :playerId, CAST(:move AS jsonb), :createdAt)
            """,
        )
            .param("sessionId", move.sessionId)
            .param("seq", move.seq)
            .param("playerId", move.playerId)
            .param("move", move.move)
            .param("createdAt", move.createdAt.toTimestamp())
            .update()
    }
}
