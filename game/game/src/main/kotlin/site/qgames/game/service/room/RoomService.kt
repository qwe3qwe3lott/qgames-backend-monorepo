package site.qgames.game.service.room

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.config.GameInstance
import site.qgames.game.config.GameProperties
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomConnectionModel
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomEventType
import site.qgames.game.model.room.RoomMemberDetailsModel
import site.qgames.game.model.room.RoomMemberModel
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomRole
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.model.room.RoomUsersModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus
import site.qgames.game.publisher.room.RoomEventPublisher
import site.qgames.game.repository.replica.UserAuthUserRepository
import site.qgames.game.repository.room.RoomConnectionRepository
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.room.RoomRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import site.qgames.game.service.game.GameService
import site.qgames.game.service.session.SessionJournalService
import site.qgames.games.api.timer.TimerModule
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Участники комнаты. Каждая мутация берёт блокировку строки комнаты ([RoomRepository.lockById]) и в той же транзакции
 * публикует событие комнаты ([RoomEventPublisher]).
 *
 * Нахождение в комнате — открытый поток её событий: подключение ([connect]) входит в комнату, а когда закрывается
 * последнее подключение пользователя ([disconnect]), ему ставится срок выхода. Подключений у пользователя может быть
 * несколько (вкладки, устройства).
 */
@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomConnectionRepository: RoomConnectionRepository,
    private val roomSessionRepository: RoomSessionRepository,
    private val sessionJournalService: SessionJournalService,
    private val userAuthUserRepository: UserAuthUserRepository,
    private val roomEventPublisher: RoomEventPublisher,
    private val gameRegistry: GameRegistry,
    private val gameService: GameService,
    private val roomDetailsService: RoomDetailsService,
    private val roomSettingsValidator: RoomSettingsValidator,
    private val jsonMapper: JsonMapper,
    private val gameInstance: GameInstance,
    private val properties: GameProperties,
    private val clock: Clock,
) {

    /**
     * Игра должна быть доступна для новых комнат (проверяется до настроек). Настройки проверяются по правилам игры;
     * ошибки — по полям тела запроса создания (`settings.…`). Создатель сразу получает срок выхода владельца
     * (qgames.game.room.leave-grace.owner): в комнате его удержит подключение к потоку, а если он так и не подключится,
     * RoomLeaveJob его удалит.
     */
    @Transactional
    fun create(userId: UUID, gameCode: GameCode, settings: RoomSettingsModel): RoomDetailsModel {
        gameService.checkPlayable(gameCode)
        roomSettingsValidator.check(gameCode, settings, fieldPrefix = "settings.")
        val now = clock.instant()
        val room = roomRepository.insert(gameCode, userId, settings.timeControl, jsonMapper.writeValueAsString(settings.gameSettings), now)
        roomMemberRepository.insert(RoomMemberModel(room.id, userId, RoomRole.PLAYER, now, now + properties.room.leaveGrace.owner))
        return roomDetailsService.details(room)
    }

    /**
     * Заменяет настройки комнаты целиком: только владелец и только между партиями. Идущую партию не меняет — у неё
     * свой снимок настроек. Ошибки настроек — по полям тела запроса (`timeControl.…`, `gameSettings.…`).
     */
    @Transactional
    fun updateSettings(userId: UUID, gameCode: GameCode, roomId: UUID, settings: RoomSettingsModel): RoomDetailsModel {
        val room = lock(roomId)
        if (room.gameCode != gameCode) {
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
        roomSettingsValidator.check(gameCode, settings, fieldPrefix = "")
        val updated = room.copy(timeControl = settings.timeControl, gameSettings = jsonMapper.writeValueAsString(settings.gameSettings))
        roomRepository.update(updated)
        roomEventPublisher.publish(roomId, RoomEventType.SETTINGS_CHANGED)
        return roomDetailsService.details(updated)
    }

    @Transactional(readOnly = true)
    fun get(userId: UUID, roomId: UUID): RoomDetailsModel {
        val room = roomRepository.findById(roomId) ?: throw RoomErrors.roomNotFound()
        gameService.checkAccessible(room.gameCode)
        return roomDetailsService.details(room)
    }

    /**
     * Подключение к потоку комнаты — вход в неё (см. [join]). В одной транзакции под блокировкой комнаты: подключение
     * с арендой, вход, снапшот комнаты для подключившегося и маркер подписчика [subscriberId]. Маркер публикуется после
     * всех событий, которые уже есть в снапшоте, — по нему поток отсекает их.
     */
    @Transactional
    fun connect(userId: UUID, roomId: UUID, subscriberId: UUID): RoomConnectionModel {
        val room = lock(roomId)
        val connectionId = roomConnectionRepository.insert(
            roomId = roomId,
            userId = userId,
            instanceId = gameInstance.id,
            expiresAt = clock.instant() + properties.room.connection.lease,
        )
        if (join(room, userId)) {
            roomEventPublisher.publish(roomId, RoomEventType.USERS_CHANGED)
        }
        val details = roomDetailsService.details(roomRepository.findById(roomId)!!)
        roomEventPublisher.publishSubscribed(roomId, subscriberId)
        return RoomConnectionModel(connectionId, details)
    }

    /**
     * Поток закрыт: подключение удаляется, и если у пользователя не осталось подключений к комнате, он выходит из неё
     * (см. [leave]). Удалённую комнату и уже удалённое подключение пропускает.
     */
    @Transactional
    fun disconnect(connectionId: UUID, roomId: UUID, userId: UUID) {
        val room = roomRepository.lockById(roomId) ?: return
        roomConnectionRepository.delete(connectionId)
        if (!roomConnectionRepository.existsForUser(roomId, userId) && leave(room, userId)) {
            roomEventPublisher.publish(roomId, RoomEventType.USERS_CHANGED)
        }
    }

    /**
     * Удаляет подключения комнаты с истёкшей арендой (их инстанс упал или потерял БД) и уводит пользователей, у которых
     * не осталось подключений, как при закрытии потока. Комната берётся под блокировку без ожидания: если её держит
     * другой запрос, она обработается в следующий раз. Возвращает число удалённых подключений.
     */
    @Transactional
    fun removeExpiredConnections(roomId: UUID): Int {
        val room = roomRepository.lockByIdSkipLocked(roomId) ?: return 0
        val users = roomConnectionRepository.deleteExpired(roomId, clock.instant())
        val left = users.toSet().count { userId -> !roomConnectionRepository.existsForUser(roomId, userId) && leave(room, userId) }
        if (left > 0) {
            roomEventPublisher.publish(roomId, RoomEventType.USERS_CHANGED)
        }
        return users.size
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
        val frozen = if (session != null && pausedAt(session) != null) roomSessionRepository.findPlayers(session.id).toSet() else emptySet()
        val expired = members
            .filter { member -> member.leaveDeadline?.let { it <= now } == true && member.userId !in frozen }
            .map { it.userId }
            .toSet()
        if (expired.isNotEmpty()) {
            removeMembers(room, members, expired, now)
            roomEventPublisher.publish(roomId, RoomEventType.USERS_CHANGED)
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
            roomEventPublisher.publish(roomId, RoomEventType.USERS_CHANGED)
        }
        return roomDetailsService.details(room)
    }

    /**
     * Удаляет комнату принудительно отключённой игры: отменяет идущую партию и удаляет комнату вместе с участниками
     * и историей партий (каскадно). Комната берётся под блокировку без ожидания: если её держит другой запрос, она
     * обработается в следующий раз. Возвращает true, если комната удалена. Журнал партии удаляется вместе с ней,
     * поэтому потоки партии закроются (диспетчер не найдёт объявленных событий), а клиенты получат 404 при
     * переподключении.
     */
    @Transactional
    fun deleteOfForceDisabledGame(roomId: UUID): Boolean {
        val room = roomRepository.lockByIdSkipLocked(roomId) ?: return false
        // Перепроверка под блокировкой: статус игры могли вернуть.
        if (!gameService.isForceDisabled(room.gameCode)) {
            return false
        }
        room.currentSessionId?.let(roomSessionRepository::findById)?.let { session ->
            sessionJournalService.cancel(session, clock.instant())
        }
        roomRepository.delete(roomId)
        roomEventPublisher.publish(roomId, RoomEventType.ROOM_DELETED)
        return true
    }

    /** Удаляет комнаты, пустующие с момента раньше [threshold]. Возвращает число удалённых. */
    @Transactional
    fun deleteEmptyRooms(threshold: Instant): Int {
        val deleted = roomRepository.deleteEmptySince(threshold)
        deleted.forEach { roomEventPublisher.publish(it, RoomEventType.ROOM_DELETED) }
        return deleted.size
    }

    /** Владелец и участники комнаты (данные события users-changed); null — комнаты уже нет. */
    @Transactional(readOnly = true)
    fun findUsers(roomId: UUID): RoomUsersModel? {
        val room = roomRepository.findById(roomId) ?: return null
        val members = roomMemberRepository.findAll(roomId)
        val profiles = userAuthUserRepository.findByIds(members.map { it.userId })
        return RoomUsersModel(room, members.map { RoomMemberDetailsModel(it, profiles[it.userId]) })
    }

    /** Настройки комнаты (данные события settings-changed); null — комнаты уже нет. */
    @Transactional(readOnly = true)
    fun findSettings(roomId: UUID): RoomSettingsModel? {
        val room = roomRepository.findById(roomId) ?: return null
        val rules = gameRegistry.rules(room.gameCode)
        return RoomSettingsModel(room.timeControl, jsonMapper.readValue(room.gameSettings, rules.settingsClass))
    }

    /**
     * Вход под блокировкой [room]. Игрок идущей партии возвращается на своё место. Иначе — игрок, если партии нет и есть
     * место, или наблюдатель. В пустой комнате вошедший становится владельцем. Вход уходящего участника (в пределах
     * срока выхода) отменяет выход: роль и владение не меняются. Повторный вход ничего не меняет.
     * Возвращает true, если участники изменились.
     */
    private fun join(room: RoomModel, userId: UUID): Boolean {
        val members = roomMemberRepository.findAll(room.id)
        val member = members.firstOrNull { it.userId == userId }
        return when {
            member == null -> {
                roomMemberRepository.insert(RoomMemberModel(room.id, userId, roleOnJoin(room, members, userId), clock.instant()))
                roomRepository.update(room.copy(ownerId = room.ownerId ?: userId, emptySince = null))
                true
            }
            member.leaveDeadline != null -> {
                roomMemberRepository.updateLeaveDeadline(room.id, userId, null)
                true
            }
            else -> false
        }
    }

    /**
     * Выход под блокировкой [room], отложенный: участник помечается уходящим и ещё [leaveDeadline] числится в комнате
     * с прежними ролью и владением, а вернувшись, ничего не теряет. Удаляет его по истечении срока RoomLeaveJob
     * ([removeExpiredMembers]). Повторный выход срок не продлевает. Возвращает true, если участники изменились.
     */
    private fun leave(room: RoomModel, userId: UUID): Boolean {
        val member = roomMemberRepository.findAll(room.id).firstOrNull { it.userId == userId } ?: return false
        if (member.leaveDeadline != null) {
            return false
        }
        roomMemberRepository.updateLeaveDeadline(room.id, userId, leaveDeadline(room, userId))
        return true
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
            return (pausedAt(session) ?: now) + gameRegistry.rules(room.gameCode).reconnectGrace
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

    private fun maxPlayers(room: RoomModel) = gameRegistry.rules(room.gameCode).maxPlayers

    /** С какого момента таймер идущей партии на паузе; null — не на паузе (или таймера нет, или партия не идёт). */
    private fun pausedAt(session: RoomSessionModel): Instant? {
        if (session.status != RoomSessionStatus.IN_PROGRESS) return null
        val timer = sessionJournalService.full(session).session.state.timer
        return if (TimerModule.isPaused(timer)) timer?.pausedAt else null
    }

    /** Комната под блокировкой. С комнатой принудительно отключённой игры работать нельзя (404 GAME_NOT_FOUND). */
    private fun lock(roomId: UUID): RoomModel {
        val room = roomRepository.lockById(roomId) ?: throw RoomErrors.roomNotFound()
        gameService.checkAccessible(room.gameCode)
        return room
    }
}
