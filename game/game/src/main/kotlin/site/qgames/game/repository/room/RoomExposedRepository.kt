package site.qgames.game.repository.room

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.mapper.room.RoomRowMapper
import site.qgames.game.mapper.room.RoomSettingsJsonMapper
import site.qgames.game.model.game.GamePublishStatus
import site.qgames.game.model.room.RoomModel
import site.qgames.game.table.game.GameTable
import site.qgames.game.table.room.RoomTable
import java.time.Instant
import java.util.UUID

/** Таблица `room` (Exposed). Используется только из [RoomRepository]. */
@Repository
@Transactional
class RoomExposedRepository(
    private val roomRowMapper: RoomRowMapper,
    private val roomSettingsJsonMapper: RoomSettingsJsonMapper,
) {

    fun insert(room: RoomModel): RoomModel {
        RoomTable.insert {
            it[id] = room.id
            write(it, room)
            it[eventSeq] = 0L
            it[createdAt] = room.createdAt
        }
        return room
    }

    fun findById(id: UUID): RoomModel? =
        RoomTable.selectAll().where { RoomTable.id eq id }.singleOrNull()?.let(roomRowMapper::toRoom)

    fun lockById(id: UUID): RoomModel? =
        RoomTable.selectAll().where { RoomTable.id eq id }.forUpdate().singleOrNull()?.let(roomRowMapper::toRoom)

    fun update(room: RoomModel) {
        RoomTable.update({ RoomTable.id eq room.id }) { write(it, room) }
    }

    fun nextEventSeq(id: UUID): Long {
        RoomTable.update({ RoomTable.id eq id }) { it.update(eventSeq, eventSeq + 1L) }
        return findEventSeq(id) ?: error("Room $id not found")
    }

    fun findEventSeq(id: UUID): Long? =
        RoomTable.select(RoomTable.eventSeq).where { RoomTable.id eq id }.singleOrNull()?.get(RoomTable.eventSeq)

    fun findIdsByGamePublishStatus(status: GamePublishStatus, limit: Int): List<UUID> =
        RoomTable
            .join(GameTable, JoinType.INNER, additionalConstraint = { GameTable.code eq RoomTable.gameCode })
            .select(RoomTable.id)
            .where { GameTable.publishStatus eq status.name }
            .orderBy(RoomTable.id to SortOrder.ASC)
            .limit(limit)
            .map { it[RoomTable.id] }

    fun delete(id: UUID) {
        RoomTable.deleteWhere { RoomTable.id eq id }
    }

    fun findIdsEmptySince(threshold: Instant, limit: Int): List<UUID> =
        RoomTable.select(RoomTable.id)
            .where { RoomTable.emptySince less threshold }
            .orderBy(RoomTable.emptySince to SortOrder.ASC)
            .limit(limit)
            .map { it[RoomTable.id] }

    /** Изменяемые колонки комнаты (всё, кроме id и created_at). */
    private fun RoomTable.write(statement: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, room: RoomModel) {
        statement[gameCode] = room.gameCode.code
        statement[ownerId] = room.ownerId
        statement[status] = room.status.name
        statement[currentSessionId] = room.currentSessionId
        statement[emptySince] = room.emptySince
        statement[settings] = roomSettingsJsonMapper.write(room.options, room.simpleSettings, room.advancedSettings)
    }
}
