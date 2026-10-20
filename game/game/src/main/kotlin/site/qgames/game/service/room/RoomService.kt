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
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.room.RoomRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.config.GameProperties
import site.qgames.game.service.game.GameRegistry
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
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
    private val roomSettingsValidator: RoomSettingsValidator,
    private val jsonMapper: JsonMapper,
    private val properties: GameProperties,
    private val clock: Clock,
) {

    /** Настройки проверяются по правилам игры; ошибки — по полям тела запроса создания (`settings.…`). */
    @Transactional
    fun create(userId: UUID, gameType: RoomGameType, settings: RoomSettingsModel): RoomDetailsModel {
        roomSettingsValidator.check(gameType, settings, fieldPrefix = "settings.")
        val now = clock.instant()
        val room = roomRepository.insert(gameType, userId, settings.timeControl, jsonMapper.writeValueAsString(settings.gameSettings), now)
        roomMemberRepository.insert(RoomMemberModel(room.id, userId, RoomRole.PLAYER, now))
        return roomDetailsService.details(room, userId)
    }

    /**
     * Заменяет настройки комнаты целиком: только владелец и только между партиями. Идущую партию не меняет — у неё
     * свой снимок настроек. Ошибки настроек — по полям тела запроса (`timeControl.…`, `gameSettings.…`).
     */
    @Transactional
    fun updateSettings(userId: UUID, gameType: RoomGameType, roomId: UUID, settings: RoomSettingsModel): RoomDetailsModel {
        val room = lock(roomId)
        if (room.gameType != gameType) {
            throw RoomErrors.gameTypeMismatch()
        }
        if (roomMemberRepository.findAll(roomId).none { it.userId == userId }) {
            throw RoomErrors.notRoomMember()
        }
        if (room.ownerId != userId) {
            throw RoomErrors.notRoomOwner()
        }
        if (room.status != RoomStatus.WAITING) {
            throw RoomErrors.roomNotWaiting()
        }
        roomSettingsValidator.check(gameType, settings, fieldPrefix = "")
        val updated = room.copy(timeControl = settings.timeControl, gameSettings = jsonMapper.writeValueAsString(settings.gameSettings))
        roomRepository.update(updated)
        return roomDetailsService.details(updated, userId)
    }

    @Transactional(readOnly = true)
    fun get(userId: UUID, roomId: UUID): RoomDetailsModel =
        roomDetailsService.details(roomRepository.findById(roomId) ?: throw RoomErrors.roomNotFound(), userId)

    /**
     * Игрок идущей партии возвращается на своё место. Иначе — игрок, если партии нет и есть место, или наблюдатель.
     * В пустой комнате вошедший становится владельцем. Вход уходящего участника (в пределах срока выхода) отменяет
     * выход: роль и владение не меняются. Повторный вход ничего не меняет.
     */
    @Transactional
    fun join(userId: UUID, roomId: UUID): RoomDetailsModel {
        val room = lock(roomId)
        val members = roomMemberRepository.findAll(roomId)
        val member = members.firstOrNull { it.userId == userId }
        when {
            member == null -> {
                roomMemberRepository.insert(RoomMemberModel(roomId, userId, roleOnJoin(room, members, userId), clock.instant()))
                roomRepository.update(room.copy(ownerId = room.ownerId ?: userId, emptySince = null))
            }
            member.leaveDeadline != null -> roomMemberRepository.updateLeaveDeadline(roomId, userId, null)
        }
        return roomDetailsService.details(roomRepository.findById(roomId)!!, userId)
    }

    /**
     * Выйти можно всегда, но выход отложенный: участник помечается уходящим и ещё [leaveDeadline] числится в комнате
     * с прежними ролью и владением, а вернувшись, ничего не теряет. Удаляет его по истечении срока RoomLeaveJob
     * ([removeExpiredMembers]). Повторный выход срок не продлевает.
     */
    @Transactional
    fun leave(userId: UUID, roomId: UUID) {
        val room = lock(roomId)
        val member = roomMemberRepository.findAll(roomId).firstOrNull { it.userId == userId } ?: throw RoomErrors.notRoomMember()
        if (member.leaveDeadline == null) {
            roomMemberRepository.updateLeaveDeadline(roomId, userId, leaveDeadline(room, userId))
        }
    }

    /**
     * Удаляет из комнаты участников с истёкшим сроком выхода, кроме игроков партии на паузе (их отсчёт заморожен).
     * Комната берётся под блокировку без ожидания: если её держит другой запрос, она обработается в следующий раз.
     * Возвращает число удалённых участников.
     */
    @Transactional
    fun removeExpiredMembers(roomId: UUID): Int {
        val room = roomRepository.lockByIdSkipLocked(roomId) ?: return 0
        val now = clock.instant()
        val members = roomMemberRepository.findAll(roomId)
        val session = room.currentSessionId?.let(roomSessionRepository::findById)
        val frozen = if (session?.pausedAt != null) roomSessionRepository.findPlayers(session.id).toSet() else emptySet()
        val expired = members
            .filter { member -> member.leaveDeadline?.let { it <= now } == true && member.userId !in frozen }
            .map { it.userId }
            .toSet()
        if (expired.isNotEmpty()) {
            removeMembers(room, members, expired, now)
        }
        return expired.size
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

    /**
     * Срок выхода. Игроку идущей партии его задаёт игра; на паузе отсчёт идёт от начала паузы, а resume сдвинет срок
     * на её длительность. Владельцу вне игры и остальным — из настроек qgames.game.room.leave-grace.
     */
    private fun leaveDeadline(room: RoomModel, userId: UUID): Instant {
        val now = clock.instant()
        val session = room.currentSessionId?.let(roomSessionRepository::findById)
        if (session != null && userId in roomSessionRepository.findPlayers(session.id)) {
            return (session.pausedAt ?: now) + gameRegistry.rules(room.gameType).reconnectGrace
        }
        return now + if (room.ownerId == userId) properties.room.leaveGrace.owner else properties.room.leaveGrace.member
    }

    /**
     * Удаляет участников [leavers]. Место игрока в идущей партии сохраняется (room_session_player). Если ушёл владелец,
     * владение передаётся по [nextOwner]. Опустевшая комната помечается для автоудаления.
     */
    private fun removeMembers(room: RoomModel, members: List<RoomMemberModel>, leavers: Set<UUID>, now: Instant) {
        leavers.forEach { roomMemberRepository.delete(room.id, it) }
        val remaining = members.filter { it.userId !in leavers }
        val owner = if (room.ownerId != null && room.ownerId in leavers) {
            nextOwner(remaining)
        } else {
            room.ownerId
        }
        roomRepository.update(room.copy(ownerId = owner, emptySince = if (remaining.isEmpty()) now else null))
    }

    /**
     * Новый владелец: сначала присутствующие (без срока выхода) — самый ранний игрок, затем наблюдатель; уходящие —
     * в том же порядке, только если присутствующих нет. null — комната опустела.
     */
    private fun nextOwner(remaining: List<RoomMemberModel>): UUID? {
        val (present, leaving) = remaining.partition { it.leaveDeadline == null }
        return sequenceOf(present, leaving)
            .mapNotNull { group -> group.firstOrNull { it.role == RoomRole.PLAYER } ?: group.firstOrNull() }
            .firstOrNull()
            ?.userId
    }

    private fun maxPlayers(room: RoomModel) = gameRegistry.rules(room.gameType).maxPlayers

    private fun lock(roomId: UUID): RoomModel = roomRepository.lockById(roomId) ?: throw RoomErrors.roomNotFound()
}
