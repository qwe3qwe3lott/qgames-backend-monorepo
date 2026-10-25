package site.qgames.game.repository.room

import org.springframework.stereotype.Repository
import site.qgames.game.model.room.RoomMemberDetailsModel
import site.qgames.game.model.room.RoomMemberModel
import site.qgames.game.model.room.RoomRole
import java.time.Instant
import java.util.UUID

@Repository
class RoomMemberRepository(
    private val roomMemberExposedRepository: RoomMemberExposedRepository,
) {

    /** Участники в порядке входа: от этого порядка зависят места в партии и передача владения. */
    fun findAll(roomId: UUID): List<RoomMemberModel> = roomMemberExposedRepository.findAll(roomId)

    /**
     * Участники в порядке входа с публичными профилями: ник и аватар читаются соединением с таблицей user модуля
     * user-auth (только чтение, через UserProfileTable из user-auth-api).
     */
    fun findAllWithProfiles(roomId: UUID): List<RoomMemberDetailsModel> = roomMemberExposedRepository.findAllWithProfiles(roomId)

    fun insert(member: RoomMemberModel) = roomMemberExposedRepository.insert(member)

    fun updateRole(roomId: UUID, userId: UUID, role: RoomRole) = roomMemberExposedRepository.updateRole(roomId, userId, role)

    /** Отмечает игрока идущей партии отсутствующим с [leftAt] или присутствующим ([leftAt] = null). */
    fun updateLeftAt(roomId: UUID, userId: UUID, leftAt: Instant?) = roomMemberExposedRepository.updateLeftAt(roomId, userId, leftAt)

    /** Комнаты, где пользователь числится участником (в том числе отсутствующим игроком партии). */
    fun findRoomIdsByUser(userId: UUID): List<UUID> = roomMemberExposedRepository.findRoomIdsByUser(userId)

    fun delete(roomId: UUID, userId: UUID) = roomMemberExposedRepository.delete(roomId, userId)

    /** Удаляет отсутствующих участников комнаты (игроков закончившейся партии) и возвращает их id. */
    fun deleteAbsent(roomId: UUID): List<UUID> = roomMemberExposedRepository.deleteAbsent(roomId)
}
