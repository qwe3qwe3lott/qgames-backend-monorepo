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
import site.qgames.game.model.room.RoomOwnerGraceModel
import site.qgames.game.model.room.RoomRole
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.model.room.RoomUsersModel
import site.qgames.game.publisher.room.RoomEventPublisher
import site.qgames.game.repository.replica.UserAuthUserRepository
import site.qgames.game.repository.room.RoomConnectionRepository
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.room.RoomRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import site.qgames.game.service.game.GameService
import site.qgames.game.service.session.SessionJournalService
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Участники комнаты. Каждая мутация берёт блокировку строки комнаты ([RoomRepository.lockById]) и в той же транзакции
 * публикует событие комнаты ([RoomEventPublisher]).
 *
 * Нахождение в комнате — открытый поток её событий: подключение ([connect]) входит в комнату, а когда закрывается
 * последнее подключение пользователя ([disconnect]), он выходит из неё. Подключений у пользователя может быть
 * несколько (вкладки, устройства). Таймеров выхода нет: ушедший удаляется сразу, кроме игрока идущей партии — тот
 * закреплён до её конца и остаётся отсутствующим (`left_at`). Ушедший владелец оставляет комнату без владельца на окно
 * владения (qgames.game.room.owner-grace): вернувшись в срок, он снова владелец, а по истечении окна владение получает
 * присутствующий участник ([endOwnerGrace]).
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
     * ошибки — по полям тела запроса создания (`settings.…`). Создатель участником не становится: комната создаётся
     * пустой, без владельца и с окном владения за создателем. Подключившись к потоку комнаты, он входит игроком и
     * становится владельцем; если не подключится, пустую комнату удалит EmptyRoomCleanupJob.
     */
    @Transactional
    fun create(userId: UUID, gameCode: GameCode, settings: RoomSettingsModel): RoomDetailsModel {
        gameService.checkAccessible(gameCode)
        roomSettingsValidator.check(gameCode, settings, fieldPrefix = "settings.")
        val now = clock.instant()
        val room = roomRepository.insert(
            gameCode = gameCode,
            creatorId = userId,
            ownerGraceDeadline = now + properties.room.ownerGrace,
            timeControl = settings.timeControl,
            gameSettings = jsonMapper.writeValueAsString(settings.gameSettings),
            now = now,
        )
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
        roomRepository.lockById(roomId) ?: return
        roomConnectionRepository.delete(connectionId)
        if (!roomConnectionRepository.existsForUser(roomId, userId) && leave(roomId, userId)) {
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
        roomRepository.lockByIdSkipLocked(roomId) ?: return 0
        val users = roomConnectionRepository.deleteExpired(roomId, clock.instant())
        val left = users.toSet().count { userId -> !roomConnectionRepository.existsForUser(roomId, userId) && leave(roomId, userId) }
        if (left > 0) {
            roomEventPublisher.publish(roomId, RoomEventType.USERS_CHANGED)
        }
        return users.size
    }

    /**
     * Окно владения истекло: владение получает присутствующий участник по [nextOwner], а если присутствующих нет,
     * комната остаётся без владельца до первого вошедшего. Комната берётся под блокировку без ожидания: если её держит
     * другой запрос, она обработается в следующий раз. Возвращает true, если окно закрыто.
     */
    @Transactional
    fun endOwnerGrace(roomId: UUID): Boolean {
        val room = roomRepository.lockByIdSkipLocked(roomId) ?: return false
        // Перепроверка под блокировкой: прежний владелец мог успеть вернуться.
        val updated = endExpiredOwnerGrace(room, roomMemberRepository.findAll(roomId), clock.instant())
        if (updated == room) {
            return false
        }
        roomRepository.update(updated)
        if (updated.ownerId != null) {
            roomEventPublisher.publish(roomId, RoomEventType.USERS_CHANGED)
        }
        return true
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
     * Удаляет комнату неопубликованной игры (NOT_PUBLISHED): отменяет идущую партию и удаляет комнату вместе с участниками
     * и историей партий (каскадно). Комната берётся под блокировку без ожидания: если её держит другой запрос, она
     * обработается в следующий раз. Возвращает true, если комната удалена. Журнал партии удаляется вместе с ней,
     * поэтому потоки партии закроются (диспетчер не найдёт объявленных событий), а клиенты получат 404 при
     * переподключении.
     */
    @Transactional
    fun deleteOfNotPublishedGame(roomId: UUID): Boolean {
        val room = roomRepository.lockByIdSkipLocked(roomId) ?: return false
        // Перепроверка под блокировкой: игру могли опубликовать снова.
        if (!gameService.isNotPublished(room.gameCode)) {
            return false
        }
        delete(room)
        return true
    }

    /**
     * Удаляет комнату, если она пустует (нет присутствующих участников) с момента раньше [threshold]. Идущая партия,
     * в которой остались только отсутствующие игроки, отменяется, журнал удаляется вместе с комнатой (как в
     * [deleteOfNotPublishedGame]). Комната берётся под блокировку без ожидания: если её держит другой запрос, она
     * обработается в следующий раз. Возвращает true, если комната удалена.
     */
    @Transactional
    fun deleteIfEmpty(roomId: UUID, threshold: Instant): Boolean {
        val room = roomRepository.lockByIdSkipLocked(roomId) ?: return false
        // Перепроверка под блокировкой: в комнату могли войти.
        val emptySince = room.emptySince
        if (emptySince == null || emptySince >= threshold) {
            return false
        }
        delete(room)
        return true
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
     * Вход под блокировкой [room]:
     * - отсутствующий игрок идущей партии снова присутствует, на своём месте;
     * - новый участник — игрок, если партии нет и есть место, игрок идущей партии — на своё место, иначе наблюдатель;
     * - повторный вход присутствующего ничего не меняет.
     *
     * Владение: ушедший владелец, вошедший в окно владения, снова владелец (роль — по входу). Если окна нет и владельца
     * нет, владельцем становится вошедший. Пока окно идёт, никто другой владельцем не становится, даже в пустой комнате.
     * Истёкшее, но ещё не закрытое джобом окно закрывается здесь же ([endExpiredOwnerGrace]).
     * Возвращает true, если участники или владелец изменились.
     */
    private fun join(room: RoomModel, userId: UUID): Boolean {
        val now = clock.instant()
        val members = roomMemberRepository.findAll(room.id)
        val current = endExpiredOwnerGrace(room, members, now)
        val member = members.firstOrNull { it.userId == userId }
        when {
            member == null -> roomMemberRepository.insert(RoomMemberModel(room.id, userId, roleOnJoin(current, members, userId), now))
            member.leftAt != null -> roomMemberRepository.updateLeftAt(room.id, userId, null)
        }
        val grace = current.ownerGrace
        val owner = when {
            current.ownerId != null -> current.ownerId
            grace == null || grace.userId == userId -> userId
            else -> null
        }
        val updated = current.copy(ownerId = owner, ownerGrace = if (owner == null) grace else null, emptySince = null)
        if (updated != room) {
            roomRepository.update(updated)
        }
        return member == null || member.leftAt != null || updated.ownerId != room.ownerId
    }

    /**
     * Выход под блокировкой комнаты [roomId]: игрок идущей партии остаётся участником и помечается отсутствующим,
     * остальные удаляются сразу. Ушёл владелец — у комнаты нет владельца, открывается окно владения за ним. Комната без
     * присутствующих помечается пустой (для автоудаления). Повторный выход ничего не меняет. Комната читается заново:
     * за одну транзакцию могут выйти несколько пользователей ([removeExpiredConnections]).
     * Возвращает true, если участники изменились.
     */
    private fun leave(roomId: UUID, userId: UUID): Boolean {
        val room = roomRepository.findById(roomId)!!
        val members = roomMemberRepository.findAll(roomId)
        val member = members.firstOrNull { it.userId == userId } ?: return false
        if (member.leftAt != null) {
            return false
        }
        val now = clock.instant()
        if (isSessionPlayer(room, userId)) {
            roomMemberRepository.updateLeftAt(roomId, userId, now)
        } else {
            roomMemberRepository.delete(roomId, userId)
        }
        val wasOwner = room.ownerId == userId
        val anyonePresent = members.any { it.userId != userId && it.leftAt == null }
        roomRepository.update(
            room.copy(
                ownerId = if (wasOwner) null else room.ownerId,
                ownerGrace = if (wasOwner) RoomOwnerGraceModel(userId, now + properties.room.ownerGrace) else room.ownerGrace,
                emptySince = if (anyonePresent) null else room.emptySince ?: now,
            ),
        )
        return true
    }

    /**
     * Комната без окна владения, если оно истекло к [now]: владелец — присутствующий участник по [nextOwner] или никто.
     * Иначе [room] как есть.
     */
    private fun endExpiredOwnerGrace(room: RoomModel, members: List<RoomMemberModel>, now: Instant): RoomModel {
        val grace = room.ownerGrace ?: return room
        if (grace.deadline > now) {
            return room
        }
        return room.copy(ownerId = nextOwner(members), ownerGrace = null)
    }

    private fun roleOnJoin(room: RoomModel, members: List<RoomMemberModel>, userId: UUID): RoomRole = when (room.status) {
        RoomStatus.IN_GAME -> if (isSessionPlayer(room, userId)) RoomRole.PLAYER else RoomRole.SPECTATOR
        RoomStatus.WAITING ->
            if (members.count { it.role == RoomRole.PLAYER } < maxPlayers(room)) RoomRole.PLAYER else RoomRole.SPECTATOR
    }

    private fun isSessionPlayer(room: RoomModel, userId: UUID): Boolean =
        room.status == RoomStatus.IN_GAME && userId in roomSessionRepository.findPlayers(room.currentSessionId!!)

    /**
     * Новый владелец — присутствующий участник: самый ранний игрок, затем наблюдатель. Отсутствующие игроки партии
     * владельцами не становятся. null — присутствующих нет.
     */
    private fun nextOwner(members: List<RoomMemberModel>): UUID? {
        val present = members.filter { it.leftAt == null }
        return (present.firstOrNull { it.role == RoomRole.PLAYER } ?: present.firstOrNull())?.userId
    }

    /** Отменяет идущую партию и удаляет комнату [room] (участники, подключения и партии — каскадно). */
    private fun delete(room: RoomModel) {
        room.currentSessionId?.let(roomSessionRepository::findById)?.let { session ->
            sessionJournalService.cancel(session, clock.instant())
        }
        roomRepository.delete(room.id)
        roomEventPublisher.publish(room.id, RoomEventType.ROOM_DELETED)
    }

    private fun maxPlayers(room: RoomModel) = gameRegistry.rules(room.gameCode).maxPlayers

    /** Комната под блокировкой. С комнатой неопубликованной игры работать нельзя (404 GAME_NOT_FOUND). */
    private fun lock(roomId: UUID): RoomModel {
        val room = roomRepository.lockById(roomId) ?: throw RoomErrors.roomNotFound()
        gameService.checkAccessible(room.gameCode)
        return room
    }
}
