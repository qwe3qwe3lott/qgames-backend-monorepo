package site.qgames.game.repository.room

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.mapper.room.RoomRowMapper
import site.qgames.game.model.room.RoomMemberDetailsModel
import site.qgames.game.model.room.RoomMemberModel
import site.qgames.game.model.room.RoomRole
import site.qgames.game.table.room.RoomMemberTable
import site.qgames.userauth.api.table.user.UserProfileTable
import java.time.Instant
import java.util.UUID

/** Таблица `room_member` (Exposed); профили — только чтение таблицы user через UserProfileTable. */
@Repository
@Transactional
class RoomMemberExposedRepository(private val roomRowMapper: RoomRowMapper) {

    fun findAll(roomId: UUID): List<RoomMemberModel> =
        RoomMemberTable.selectAll().where { RoomMemberTable.roomId eq roomId }.inJoinOrder().map(roomRowMapper::toMember)

    fun findAllWithProfiles(roomId: UUID): List<RoomMemberDetailsModel> =
        RoomMemberTable
            .join(UserProfileTable, JoinType.LEFT, additionalConstraint = { UserProfileTable.id eq RoomMemberTable.userId })
            .selectAll()
            .where { RoomMemberTable.roomId eq roomId }
            .inJoinOrder()
            .map { RoomMemberDetailsModel(roomRowMapper.toMember(it), roomRowMapper.toProfile(it)) }

    fun insert(member: RoomMemberModel) {
        RoomMemberTable.insert {
            it[roomId] = member.roomId
            it[userId] = member.userId
            it[role] = member.role.name
            it[joinedAt] = member.joinedAt
            it[leftAt] = member.leftAt
        }
    }

    fun updateRole(roomId: UUID, userId: UUID, role: RoomRole) {
        RoomMemberTable.update({ member(roomId, userId) }) { it[RoomMemberTable.role] = role.name }
    }

    fun updateLeftAt(roomId: UUID, userId: UUID, leftAt: Instant?) {
        RoomMemberTable.update({ member(roomId, userId) }) { it[RoomMemberTable.leftAt] = leftAt }
    }

    fun findRoomIdsByUser(userId: UUID): List<UUID> =
        RoomMemberTable.select(RoomMemberTable.roomId).where { RoomMemberTable.userId eq userId }.map { it[RoomMemberTable.roomId] }

    fun delete(roomId: UUID, userId: UUID) {
        RoomMemberTable.deleteWhere { member(roomId, userId) }
    }

    /** Выборка и удаление — в одной пишущей транзакции (под блокировкой комнаты), без RETURNING ради переносимости. */
    fun deleteAbsent(roomId: UUID): List<UUID> {
        val absent = RoomMemberTable.select(RoomMemberTable.userId)
            .where { (RoomMemberTable.roomId eq roomId) and RoomMemberTable.leftAt.isNotNull() }
            .map { it[RoomMemberTable.userId] }
        if (absent.isNotEmpty()) {
            RoomMemberTable.deleteWhere { (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId inList absent) }
        }
        return absent
    }

    private fun member(roomId: UUID, userId: UUID) = (RoomMemberTable.roomId eq roomId) and (RoomMemberTable.userId eq userId)

    private fun Query.inJoinOrder() = orderBy(RoomMemberTable.joinedAt to SortOrder.ASC, RoomMemberTable.userId to SortOrder.ASC)
}
