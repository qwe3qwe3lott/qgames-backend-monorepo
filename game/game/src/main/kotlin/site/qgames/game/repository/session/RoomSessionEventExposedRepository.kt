package site.qgames.game.repository.session

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.mapper.session.RoomSessionRowMapper
import site.qgames.game.mapper.session.SessionEventJsonMapper
import site.qgames.game.model.session.RoomSessionEventModel
import site.qgames.game.table.session.RoomSessionEventTable
import site.qgames.games.api.session.SessionEvent
import java.time.Instant
import java.util.UUID

/** Таблица `room_session_event` — журнал партии (Exposed). Используется только из [RoomSessionEventRepository]. */
@Repository
@Transactional
class RoomSessionEventExposedRepository(
    private val roomSessionRowMapper: RoomSessionRowMapper,
    private val sessionEventJsonMapper: SessionEventJsonMapper,
) {

    fun insert(sessionId: UUID, seq: Long, event: SessionEvent, createdAt: Instant) {
        RoomSessionEventTable.insert {
            it[RoomSessionEventTable.sessionId] = sessionId
            it[RoomSessionEventTable.seq] = seq
            it[method] = event.method.name
            it[code] = event.code
            it[data] = sessionEventJsonMapper.write(event)
            it[RoomSessionEventTable.createdAt] = createdAt
        }
    }

    fun findRange(sessionId: UUID, fromSeq: Long, toSeq: Long): List<RoomSessionEventModel> =
        RoomSessionEventTable.selectAll()
            .where { (RoomSessionEventTable.sessionId eq sessionId) and RoomSessionEventTable.seq.between(fromSeq, toSeq) }
            .orderBy(RoomSessionEventTable.seq to SortOrder.ASC)
            .map(roomSessionRowMapper::toEvent)
}
