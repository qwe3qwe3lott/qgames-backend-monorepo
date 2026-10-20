package site.qgames.game.repository.room

import org.springframework.stereotype.Repository
import site.qgames.game.mapper.room.RoomEntityMapper
import site.qgames.game.model.room.RoomMemberModel
import site.qgames.game.model.room.RoomRole
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class RoomMemberRepository(
    private val roomMemberPostgresqlRepository: RoomMemberPostgresqlRepository,
    private val roomEntityMapper: RoomEntityMapper,
) {

    /** Участники в порядке входа: от этого порядка зависят места в партии и передача владения. */
    fun findAll(roomId: UUID): List<RoomMemberModel> =
        roomMemberPostgresqlRepository.findAll(roomId).map(roomEntityMapper::toModel)

    fun insert(member: RoomMemberModel) = roomMemberPostgresqlRepository.insert(roomEntityMapper.toEntity(member))

    fun updateRole(roomId: UUID, userId: UUID, role: RoomRole) =
        roomMemberPostgresqlRepository.updateRole(roomId, userId, role.name)

    /** Ставит срок отложенного выхода или снимает его ([leaveDeadline] = null). */
    fun updateLeaveDeadline(roomId: UUID, userId: UUID, leaveDeadline: Instant?) =
        roomMemberPostgresqlRepository.updateLeaveDeadline(roomId, userId, leaveDeadline)

    /** Сдвигает сроки выхода участников [userIds] на [shift]; у присутствующих (без срока) ничего не меняется. */
    fun shiftLeaveDeadlines(roomId: UUID, userIds: Collection<UUID>, shift: Duration) =
        roomMemberPostgresqlRepository.shiftLeaveDeadlines(roomId, userIds, shift.toNanos() / 1_000)

    /** Комнаты с истёкшими к [now] сроками выхода (без игроков партий на паузе), самые просроченные первыми. Без блокировок. */
    fun findRoomIdsWithExpiredLeave(now: Instant, limit: Int): List<UUID> =
        roomMemberPostgresqlRepository.findRoomIdsWithExpiredLeave(now, limit)

    fun delete(roomId: UUID, userId: UUID) = roomMemberPostgresqlRepository.delete(roomId, userId)
}
