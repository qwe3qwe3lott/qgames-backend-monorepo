package site.qgames.game.repository.session

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.mapper.room.RoomSettingsJsonMapper
import site.qgames.game.mapper.session.RoomSessionRowMapper
import site.qgames.game.model.game.GamePublishStatus
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus
import site.qgames.game.table.game.GameTable
import site.qgames.game.table.session.RoomSessionPlayerTable
import site.qgames.game.table.session.RoomSessionTable
import java.time.Instant
import java.util.UUID

/** Таблицы `room_session` и `room_session_player` (Exposed). Используется только из [RoomSessionRepository]. */
@Repository
@Transactional
class RoomSessionExposedRepository(
    private val roomSessionRowMapper: RoomSessionRowMapper,
    private val roomSettingsJsonMapper: RoomSettingsJsonMapper,
) {

    fun insert(session: RoomSessionModel, players: List<UUID>): RoomSessionModel {
        RoomSessionTable.insert {
            it[id] = session.id
            it[roomId] = session.roomId
            it[gameCode] = session.gameCode.code
            it[status] = session.status.name
            it[seed] = session.seed
            it[lastSeq] = session.lastSeq
            it[gameSettings] = roomSettingsJsonMapper.write(session.timeControl, session.gameSettings)
            it[moveDeadline] = session.moveDeadline
            it[createdAt] = session.createdAt
            it[finishedAt] = session.finishedAt
        }
        players.forEachIndexed { index, player ->
            RoomSessionPlayerTable.insert {
                it[sessionId] = session.id
                it[seat] = index
                it[userId] = player
            }
        }
        return session
    }

    fun findById(id: UUID): RoomSessionModel? =
        RoomSessionTable.selectAll().where { RoomSessionTable.id eq id }.singleOrNull()?.let(roomSessionRowMapper::toSession)

    fun findLatest(roomId: UUID): RoomSessionModel? =
        RoomSessionTable.selectAll()
            .where { RoomSessionTable.roomId eq roomId }
            .orderBy(RoomSessionTable.createdAt to SortOrder.DESC, RoomSessionTable.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let(roomSessionRowMapper::toSession)

    fun findPlayers(sessionId: UUID): List<UUID> =
        RoomSessionPlayerTable.select(RoomSessionPlayerTable.userId)
            .where { RoomSessionPlayerTable.sessionId eq sessionId }
            .orderBy(RoomSessionPlayerTable.seat to SortOrder.ASC)
            .map { it[RoomSessionPlayerTable.userId] }

    /** Таймер на паузе дедлайна в проекции не имеет, поэтому такие партии не попадают в выборку. */
    fun findIdsWithExpiredMove(now: Instant, limit: Int): List<UUID> =
        RoomSessionTable
            .join(GameTable, JoinType.INNER, additionalConstraint = { GameTable.code eq RoomSessionTable.gameCode })
            .select(RoomSessionTable.id)
            .where {
                (RoomSessionTable.status eq RoomSessionStatus.IN_PROGRESS.name) and
                    (RoomSessionTable.moveDeadline lessEq now) and
                    (GameTable.publishStatus neq GamePublishStatus.NOT_PUBLISHED.name)
            }
            .orderBy(RoomSessionTable.moveDeadline to SortOrder.ASC)
            .limit(limit)
            .map { it[RoomSessionTable.id] }

    fun advance(id: UUID, lastSeq: Long, status: RoomSessionStatus, moveDeadline: Instant?, finishedAt: Instant?) {
        RoomSessionTable.update({ RoomSessionTable.id eq id }) {
            it[RoomSessionTable.lastSeq] = lastSeq
            it[RoomSessionTable.status] = status.name
            it[RoomSessionTable.moveDeadline] = moveDeadline
            it[RoomSessionTable.finishedAt] = finishedAt
        }
    }
}
