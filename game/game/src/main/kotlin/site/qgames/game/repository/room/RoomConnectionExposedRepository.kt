package site.qgames.game.repository.room

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.table.room.RoomConnectionTable
import site.qgames.game.util.newId
import java.time.Instant
import java.util.UUID

/**
 * Таблица `room_connection` (Exposed). Используется только из [RoomConnectionRepository]. Выборка и изменение одних и
 * тех же строк идут в одной пишущей транзакции, без RETURNING ради переносимости.
 */
@Repository
@Transactional
class RoomConnectionExposedRepository {

    fun insert(roomId: UUID, userId: UUID, expiresAt: Instant): UUID {
        val id = newId()
        RoomConnectionTable.insert {
            it[RoomConnectionTable.id] = id
            it[RoomConnectionTable.roomId] = roomId
            it[RoomConnectionTable.userId] = userId
            it[RoomConnectionTable.expiresAt] = expiresAt
        }
        return id
    }

    fun delete(id: UUID): Boolean = RoomConnectionTable.deleteWhere { RoomConnectionTable.id eq id } > 0

    fun deleteForUser(roomId: UUID, userId: UUID) {
        RoomConnectionTable.deleteWhere { (RoomConnectionTable.roomId eq roomId) and (RoomConnectionTable.userId eq userId) }
    }

    fun existsForUser(roomId: UUID, userId: UUID): Boolean =
        !RoomConnectionTable.select(RoomConnectionTable.id)
            .where { (RoomConnectionTable.roomId eq roomId) and (RoomConnectionTable.userId eq userId) }
            .limit(1)
            .empty()

    fun renew(ids: Collection<UUID>, expiresAt: Instant): List<UUID> {
        val existing = RoomConnectionTable.select(RoomConnectionTable.id)
            .where { RoomConnectionTable.id inList ids }
            .map { it[RoomConnectionTable.id] }
        if (existing.isNotEmpty()) {
            RoomConnectionTable.update({ RoomConnectionTable.id inList existing }) { it[RoomConnectionTable.expiresAt] = expiresAt }
        }
        return existing
    }

    fun findRoomIdsWithExpired(now: Instant, limit: Int): List<UUID> {
        val earliest = RoomConnectionTable.expiresAt.min()
        return RoomConnectionTable.select(RoomConnectionTable.roomId, earliest)
            .where { RoomConnectionTable.expiresAt lessEq now }
            .groupBy(RoomConnectionTable.roomId)
            .orderBy(earliest to SortOrder.ASC)
            .limit(limit)
            .map { it[RoomConnectionTable.roomId] }
    }

    fun deleteExpired(roomId: UUID, now: Instant): List<UUID> {
        val expired = RoomConnectionTable.select(RoomConnectionTable.id, RoomConnectionTable.userId)
            .where { (RoomConnectionTable.roomId eq roomId) and (RoomConnectionTable.expiresAt lessEq now) }
            .map { it[RoomConnectionTable.id] to it[RoomConnectionTable.userId] }
        if (expired.isNotEmpty()) {
            RoomConnectionTable.deleteWhere { RoomConnectionTable.id inList expired.map { it.first } }
        }
        return expired.map { it.second }
    }
}
