package site.qgames.game.service.room

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.config.GameProperties
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomConnectionModel
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomMemberModel
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomRole
import site.qgames.game.model.room.RoomSettingsUpdateModel
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.publisher.room.RoomEventPublisher
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
 * закреплён до её конца и остаётся отсутствующим (`left_at`). Ушёл владелец — владение сразу получает присутствующий
 * участник ([nextOwner]); в комнате без присутствующих владельцем становится первый вошедший.
 */
@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomConnectionRepository: RoomConnectionRepository,
    private val roomSessionRepository: RoomSessionRepository,
    private val sessionJournalService: SessionJournalService,
    private val roomEventPublisher: RoomEventPublisher,
    private val gameRegistry: GameRegistry,
    private val gameService: GameService,
    private val roomDetailsService: RoomDetailsService,
    private val roomSettingsValidator: RoomSettingsValidator,
    private val jsonMapper: JsonMapper,
    private val properties: GameProperties,
    private val clock: Clock,
) {

    /**
     * Комната с настройками игры по умолчанию (GameService.defaultSettings). Игра должна быть доступна для новых комнат.
     * Создатель участником не становится: комната создаётся пустой и без владельца. Подключившись к потоку комнаты
     * первым, он входит игроком и становится владельцем; если не подключится, пустую комнату удалит EmptyRoomCleanupJob.
     */
    @Transactional
    fun create(gameCode: GameCode): RoomDetailsModel {
        gameService.checkAccessible(gameCode)
        val settings = gameService.defaultSettings(gameCode)
        val room = roomRepository.insert(
            gameCode = gameCode,
            options = settings.room,
            simpleSettings = jsonMapper.writeValueAsString(settings.simple),
            advancedSettings = jsonMapper.writeValueAsString(settings.advanced),
            now = clock.instant(),
        )
        return roomDetailsService.details(room)
    }

    /**
     * Заменяет переданные группы настроек целиком, только владелец. room — в любой момент (понижение лимита наблюдателей
     * вошедших не выводит); simple и advanced — только между партиями: simple переводит комнату в simple-режим
     * (advanced выводится из него), advanced — в advanced-режим. Идущую партию не меняет — у неё свой снимок advanced.
     * Ошибки настроек — по полям тела запроса (`room.…`, `simple.…`, `advanced.…`).
     */
    @Transactional
    fun updateSettings(userId: UUID, gameCode: GameCode, roomId: UUID, update: RoomSettingsUpdateModel) {
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
        roomSettingsValidator.checkUpdate(gameCode, update)
        if ((update.simple != null || update.advanced != null) && room.status != RoomStatus.WAITING) {
            throw RoomErrors.roomNotWaiting()
        }
        val rules = gameRegistry.rules(gameCode)
        val simple = update.simple
        val advanced = update.advanced
        val updated = when {
            simple != null -> room.copy(
                simpleSettings = jsonMapper.writeValueAsString(simple),
                advancedSettings = jsonMapper.writeValueAsString(rules.toAdvanced(simple)),
            )
            advanced != null -> room.copy(simpleSettings = null, advancedSettings = jsonMapper.writeValueAsString(advanced))
            else -> room
        }.copy(options = update.room ?: room.options)
        if (updated != room) {
            roomRepository.update(updated)
            roomEventPublisher.settingsChanged(roomId, roomDetailsService.settings(updated))
        }
    }

    @Transactional(readOnly = true)
    fun get(userId: UUID, roomId: UUID): RoomDetailsModel {
        val room = roomRepository.findById(roomId) ?: throw RoomErrors.roomNotFound()
        gameService.checkAccessible(room.gameCode)
        return roomDetailsService.details(room)
    }

    /**
     * Подключение к потоку комнаты — вход в неё (см. [join]). В одной транзакции под блокировкой комнаты: подключение
     * с арендой, вход, снапшот комнаты для подключившегося и seq последнего события: события входа уже в снапшоте, по
     * seq поток отсекает их.
     */
    @Transactional
    fun connect(userId: UUID, roomId: UUID): RoomConnectionModel {
        val room = lock(roomId)
        val connectionId = roomConnectionRepository.insert(
            roomId = roomId,
            userId = userId,
            expiresAt = clock.instant() + properties.room.connection.lease,
        )
        join(room, userId)
        val details = roomDetailsService.details(roomRepository.findById(roomId)!!)
        return RoomConnectionModel(connectionId, roomRepository.findEventSeq(roomId)!!, details)
    }

    /**
     * Поток закрыт: подключение удаляется, и если у пользователя не осталось подключений к комнате, он выходит из неё
     * (см. [leave]). Удалённую комнату и уже удалённое подключение пропускает.
     */
    @Transactional
    fun disconnect(connectionId: UUID, roomId: UUID, userId: UUID) {
        roomRepository.lockById(roomId) ?: return
        roomConnectionRepository.delete(connectionId)
        if (!roomConnectionRepository.existsForUser(roomId, userId)) {
            leave(roomId, userId)
        }
    }

    /**
     * Удаляет подключения комнаты с истёкшей арендой (их никто не продлевает) и уводит пользователей, у которых не
     * осталось подключений, как при закрытии потока. Возвращает число удалённых подключений; удалённую комнату пропускает.
     */
    @Transactional
    fun removeExpiredConnections(roomId: UUID): Int {
        roomRepository.lockById(roomId) ?: return 0
        val users = roomConnectionRepository.deleteExpired(roomId, clock.instant())
        users.toSet().filter { !roomConnectionRepository.existsForUser(roomId, it) }.forEach { leave(roomId, it) }
        return users.size
    }

    /**
     * Владелец передаёт владение присутствующему участнику [newOwnerId] — в любой момент, в том числе во время партии.
     * Отсутствующий игрок партии владельцем не становится. Передача самому себе ничего не меняет.
     */
    @Transactional
    fun transferOwnership(userId: UUID, roomId: UUID, newOwnerId: UUID) {
        val room = lock(roomId)
        val members = roomMemberRepository.findAll(roomId)
        if (members.none { it.userId == userId }) {
            throw RoomErrors.notRoomMember()
        }
        if (room.ownerId != userId) {
            throw RoomErrors.notRoomOwner()
        }
        if (members.none { it.userId == newOwnerId && it.leftAt == null }) {
            throw RoomErrors.newOwnerNotPresent()
        }
        if (newOwnerId == userId) {
            return
        }
        roomRepository.update(room.copy(ownerId = newOwnerId))
        roomEventPublisher.ownerChanged(roomId, newOwnerId)
    }

    /**
     * Владелец исключает участника [targetId] — в любой момент, в том числе игрока идущей партии. Это не бан: исключённый
     * может войти снова. Его подключения удаляются (потоки комнаты закроет событие member-kicked), дальше — как выход
     * ([leave]): игрок партии остаётся отсутствующим до её конца, остальные удаляются. Неучастника и уже отсутствующего
     * не трогает.
     */
    @Transactional
    fun kick(userId: UUID, roomId: UUID, targetId: UUID) {
        val room = lock(roomId)
        val members = roomMemberRepository.findAll(roomId)
        if (members.none { it.userId == userId }) {
            throw RoomErrors.notRoomMember()
        }
        if (room.ownerId != userId) {
            throw RoomErrors.notRoomOwner()
        }
        if (targetId == userId) {
            throw RoomErrors.cannotKickSelf()
        }
        if (members.none { it.userId == targetId && it.leftAt == null }) {
            return
        }
        roomConnectionRepository.deleteForUser(roomId, targetId)
        roomEventPublisher.memberKicked(roomId, targetId)
        leave(roomId, targetId)
    }

    /** Роли меняются только между партиями. */
    @Transactional
    fun changeRole(userId: UUID, roomId: UUID, role: RoomRole) {
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
            if (role == RoomRole.SPECTATOR && !hasSpectatorSeat(room, members)) {
                throw RoomErrors.noSpectatorSeat()
            }
            roomMemberRepository.updateRole(roomId, userId, role)
            roomEventPublisher.roleChanged(roomId, userId, role)
        }
    }

    /**
     * Удаляет комнату неопубликованной игры (NOT_PUBLISHED): отменяет идущую партию и удаляет комнату вместе с участниками
     * и историей партий (каскадно). Возвращает true, если комната удалена. Журнал партии удаляется вместе с ней,
     * поэтому потоки партии закроются (диспетчер не найдёт объявленных событий), а клиенты получат 404 при
     * переподключении.
     */
    @Transactional
    fun deleteOfNotPublishedGame(roomId: UUID): Boolean {
        val room = roomRepository.lockById(roomId) ?: return false
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
     * [deleteOfNotPublishedGame]). Возвращает true, если комната удалена.
     */
    @Transactional
    fun deleteIfEmpty(roomId: UUID, threshold: Instant): Boolean {
        val room = roomRepository.lockById(roomId) ?: return false
        // Перепроверка под блокировкой: в комнату могли войти.
        val emptySince = room.emptySince
        if (emptySince == null || emptySince >= threshold) {
            return false
        }
        delete(room)
        return true
    }

    /** Комнаты, в которых участвует [userId]. Своя транзакция: вызывается после коммита транзакции user-auth. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun findRoomIdsByUser(userId: UUID): List<UUID> = roomMemberRepository.findRoomIdsByUser(userId)

    /**
     * Участник [userId] сменил ник или аватар: событие profile-changed в комнату [roomId], под её блокировкой (seq).
     * Профиль читается здесь же, как его покажет снапшот. Удалённую комнату и ушедшего участника пропускает. Своя
     * транзакция: вызывается после коммита транзакции user-auth (UserProfileListener), по одной на комнату.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun profileChanged(roomId: UUID, userId: UUID) {
        roomRepository.lockById(roomId) ?: return
        val member = roomMemberRepository.findAllWithProfiles(roomId).firstOrNull { it.member.userId == userId } ?: return
        roomEventPublisher.profileChanged(roomId, member)
    }

    /**
     * Вход под блокировкой [room]:
     * - отсутствующий игрок идущей партии снова присутствует, на своём месте;
     * - новый участник — игрок, если партии нет и есть место, игрок идущей партии — на своё место, иначе наблюдатель,
     *   если наблюдателей меньше лимита комнаты (иначе 409 NO_SPECTATOR_SEAT);
     * - повторный вход присутствующего ничего не меняет.
     *
     * Владение: в комнате без владельца (никого не было) владельцем становится вошедший.
     * Изменения публикуются событиями потока: member-joined или member-returned, затем owner-changed.
     */
    private fun join(room: RoomModel, userId: UUID) {
        val now = clock.instant()
        val members = roomMemberRepository.findAll(room.id)
        val member = members.firstOrNull { it.userId == userId }
        when {
            member == null -> {
                val role = roleOnJoin(room, members, userId)
                if (role == RoomRole.SPECTATOR && !hasSpectatorSeat(room, members)) {
                    throw RoomErrors.noSpectatorSeat()
                }
                roomMemberRepository.insert(RoomMemberModel(room.id, userId, role, now))
                val joined = roomMemberRepository.findAllWithProfiles(room.id).first { it.member.userId == userId }
                roomEventPublisher.memberJoined(room.id, joined)
            }
            member.leftAt != null -> {
                roomMemberRepository.updateLeftAt(room.id, userId, null)
                roomEventPublisher.memberReturned(room.id, userId)
            }
        }
        val updated = room.copy(ownerId = room.ownerId ?: userId, emptySince = null)
        if (updated != room) {
            roomRepository.update(updated)
        }
        if (updated.ownerId != room.ownerId) {
            roomEventPublisher.ownerChanged(room.id, updated.ownerId)
        }
    }

    /**
     * Выход под блокировкой комнаты [roomId]: игрок идущей партии остаётся участником и помечается отсутствующим,
     * остальные удаляются сразу. Ушёл владелец — владение сразу получает присутствующий участник ([nextOwner]), а если
     * присутствующих нет, владельца нет до первого вошедшего. Комната без присутствующих помечается пустой (для
     * автоудаления). Повторный выход ничего не меняет. Комната читается заново: за одну транзакцию могут выйти несколько
     * пользователей ([removeExpiredConnections]).
     * Изменения публикуются событиями потока: member-leaving или member-left, затем owner-changed.
     */
    private fun leave(roomId: UUID, userId: UUID) {
        val room = roomRepository.findById(roomId)!!
        val members = roomMemberRepository.findAll(roomId)
        val member = members.firstOrNull { it.userId == userId } ?: return
        if (member.leftAt != null) {
            return
        }
        val now = clock.instant()
        if (isSessionPlayer(room, userId)) {
            roomMemberRepository.updateLeftAt(roomId, userId, now)
            roomEventPublisher.memberLeaving(roomId, userId)
        } else {
            roomMemberRepository.delete(roomId, userId)
            roomEventPublisher.memberLeft(roomId, userId)
        }
        val others = members.filter { it.userId != userId }
        val anyonePresent = others.any { it.leftAt == null }
        val updated = room.copy(
            ownerId = if (room.ownerId == userId) nextOwner(others) else room.ownerId,
            emptySince = if (anyonePresent) null else room.emptySince ?: now,
        )
        roomRepository.update(updated)
        if (updated.ownerId != room.ownerId) {
            roomEventPublisher.ownerChanged(roomId, updated.ownerId)
        }
    }

    private fun roleOnJoin(room: RoomModel, members: List<RoomMemberModel>, userId: UUID): RoomRole = when (room.status) {
        RoomStatus.IN_GAME -> if (isSessionPlayer(room, userId)) RoomRole.PLAYER else RoomRole.SPECTATOR
        RoomStatus.WAITING ->
            if (members.count { it.role == RoomRole.PLAYER } < maxPlayers(room)) RoomRole.PLAYER else RoomRole.SPECTATOR
    }

    private fun isSessionPlayer(room: RoomModel, userId: UUID): Boolean =
        room.status == RoomStatus.IN_GAME && userId in roomSessionRepository.findPlayers(room.currentSessionId!!)

    /**
     * Новый владелец — случайный присутствующий игрок, а если игроков нет — случайный наблюдатель. Отсутствующие игроки
     * партии владельцами не становятся. null — присутствующих нет.
     */
    private fun nextOwner(members: List<RoomMemberModel>): UUID? {
        val present = members.filter { it.leftAt == null }
        return (present.filter { it.role == RoomRole.PLAYER }.randomOrNull() ?: present.randomOrNull())?.userId
    }

    /** Отменяет идущую партию и удаляет комнату [room] (участники, подключения и партии — каскадно). */
    private fun delete(room: RoomModel) {
        room.currentSessionId?.let(roomSessionRepository::findById)?.let { session ->
            sessionJournalService.cancel(session, clock.instant())
        }
        roomEventPublisher.roomDeleted(room.id)
        roomRepository.delete(room.id)
    }

    private fun maxPlayers(room: RoomModel) = gameRegistry.rules(room.gameCode).maxPlayers

    /** Наблюдателей меньше лимита комнаты: после понижения лимита их может быть и больше, новые тогда не входят. */
    private fun hasSpectatorSeat(room: RoomModel, members: List<RoomMemberModel>) =
        members.count { it.role == RoomRole.SPECTATOR } < room.options.maxSpectators

    /** Комната под блокировкой. С комнатой неопубликованной игры работать нельзя (404 GAME_NOT_FOUND). */
    private fun lock(roomId: UUID): RoomModel {
        val room = roomRepository.lockById(roomId) ?: throw RoomErrors.roomNotFound()
        gameService.checkAccessible(room.gameCode)
        return room
    }
}
