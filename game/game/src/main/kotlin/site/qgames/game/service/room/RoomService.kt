package site.qgames.game.service.room

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomMemberModel
import site.qgames.game.model.room.RoomRole
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.room.RoomGameType
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.room.RoomRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import java.time.Clock
import java.util.UUID

/**
 * Участники комнаты. Каждая мутация берёт блокировку строки комнаты ([RoomRepository.lockById]).
 */
@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomSessionRepository: RoomSessionRepository,
    private val gameRegistry: GameRegistry,
    private val roomDetailsService: RoomDetailsService,
    private val clock: Clock,
) {

    @Transactional
    fun create(userId: UUID, gameType: RoomGameType): RoomDetailsModel {
        val now = clock.instant()
        val room = roomRepository.insert(gameType, userId, now)
        roomMemberRepository.insert(RoomMemberModel(room.id, userId, RoomRole.PLAYER, now))
        return roomDetailsService.details(room, userId)
    }

    @Transactional(readOnly = true)
    fun get(userId: UUID, roomId: UUID): RoomDetailsModel =
        roomDetailsService.details(roomRepository.findById(roomId) ?: throw RoomErrors.roomNotFound(), userId)

    /**
     * Игрок идущей партии возвращается на своё место. Иначе — игрок, если партии нет и есть место, или наблюдатель.
     * В пустой комнате вошедший становится владельцем. Повторный вход ничего не меняет.
     */
    @Transactional
    fun join(userId: UUID, roomId: UUID): RoomDetailsModel {
        val room = lock(roomId)
        val members = roomMemberRepository.findAll(roomId)
        if (members.none { it.userId == userId }) {
            roomMemberRepository.insert(RoomMemberModel(roomId, userId, roleOnJoin(room, members, userId), clock.instant()))
            roomRepository.update(room.copy(ownerId = room.ownerId ?: userId, emptySince = null))
        }
        return roomDetailsService.details(roomRepository.findById(roomId)!!, userId)
    }

    /**
     * Выйти можно всегда; место в идущей партии сохраняется. Владение переходит к самому раннему из оставшихся
     * игроков, затем наблюдателей. Опустевшая комната помечается для автоудаления.
     */
    @Transactional
    fun leave(userId: UUID, roomId: UUID) {
        val room = lock(roomId)
        val members = roomMemberRepository.findAll(roomId)
        if (members.none { it.userId == userId }) {
            throw RoomErrors.notRoomMember()
        }
        roomMemberRepository.delete(roomId, userId)
        val remaining = members.filter { it.userId != userId }
        val owner = if (room.ownerId == userId) {
            (remaining.firstOrNull { it.role == RoomRole.PLAYER } ?: remaining.firstOrNull())?.userId
        } else {
            room.ownerId
        }
        roomRepository.update(room.copy(ownerId = owner, emptySince = if (remaining.isEmpty()) clock.instant() else null))
    }

    /** Роли меняются только между партиями. */
    @Transactional
    fun changeRole(userId: UUID, roomId: UUID, role: RoomRole): RoomDetailsModel {
        val room = lock(roomId)
        val members = roomMemberRepository.findAll(roomId)
        val member = members.firstOrNull { it.userId == userId } ?: throw RoomErrors.notRoomMember()
        if (room.status != RoomStatus.WAITING) {
            throw RoomErrors.roomNotWaiting()
        }
        if (member.role != role) {
            if (role == RoomRole.PLAYER && members.count { it.role == RoomRole.PLAYER } >= maxPlayers(room)) {
                throw RoomErrors.noFreeSeat()
            }
            roomMemberRepository.updateRole(roomId, userId, role)
        }
        return roomDetailsService.details(room, userId)
    }

    @Transactional
    fun delete(userId: UUID, roomId: UUID) {
        val room = lock(roomId)
        if (room.ownerId != userId) {
            throw RoomErrors.notRoomOwner()
        }
        roomRepository.delete(roomId)
    }

    private fun roleOnJoin(room: RoomModel, members: List<RoomMemberModel>, userId: UUID): RoomRole = when (room.status) {
        RoomStatus.IN_GAME ->
            if (userId in roomSessionRepository.findPlayers(room.currentSessionId!!)) RoomRole.PLAYER else RoomRole.SPECTATOR
        RoomStatus.WAITING ->
            if (members.count { it.role == RoomRole.PLAYER } < maxPlayers(room)) RoomRole.PLAYER else RoomRole.SPECTATOR
    }

    private fun maxPlayers(room: RoomModel) = gameRegistry.rules(room.gameType).maxPlayers

    private fun lock(roomId: UUID): RoomModel = roomRepository.lockById(roomId) ?: throw RoomErrors.roomNotFound()
}
