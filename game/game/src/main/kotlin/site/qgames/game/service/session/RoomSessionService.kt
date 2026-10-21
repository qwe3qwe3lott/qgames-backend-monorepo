package site.qgames.game.service.session

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomEventType
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomRole
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.publisher.room.RoomEventPublisher
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.room.RoomRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import site.qgames.game.service.game.GameService
import site.qgames.game.service.room.RoomDetailsService
import site.qgames.games.api.TimeControl
import site.qgames.games.api.TimeControlMode
import site.qgames.games.api.session.MoveOutcome
import site.qgames.games.api.session.SessionDocument
import site.qgames.games.api.session.SessionEngine
import site.qgames.games.api.session.SessionStatus
import site.qgames.games.api.session.TimerOutcome
import tools.jackson.databind.json.JsonMapper
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Партии в комнате. Партия — журнал событий ([SessionJournalService]); правила конкретной игры и модули — в
 * [SessionEngine]. Все операции идут под блокировкой строки комнаты: полное состояние на last_seq → события → запись.
 *
 * Пауза — действие таймера: замораживает дедлайн хода (окно владения не замораживает); у партии без таймера паузы
 * нет. Старт, отмена и конец партии публикуют событие комнаты session-changed (по нему клиент узнаёт, на какую партию
 * подписаться); всё остальное — события потока партии.
 */
@Service
class RoomSessionService(
    private val roomRepository: RoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomSessionRepository: RoomSessionRepository,
    private val sessionJournalService: SessionJournalService,
    private val gameRegistry: GameRegistry,
    private val gameService: GameService,
    private val roomDetailsService: RoomDetailsService,
    private val roomEventPublisher: RoomEventPublisher,
    private val jsonMapper: JsonMapper,
    private val sessionEngine: SessionEngine,
    private val clock: Clock,
) {

    private val random = SecureRandom()

    /**
     * Игроки — участники с ролью PLAYER, места — по порядку входа в комнату. Партия получает снимок настроек
     * комнаты, первое событие журнала — session-started. Игра должна быть доступна для новых партий.
     */
    @Transactional
    fun start(userId: UUID, roomId: UUID): RoomDetailsModel {
        val room = lockAsOwner(userId, roomId)
        gameService.checkPlayable(room.gameCode)
        if (room.status != RoomStatus.WAITING) {
            throw RoomErrors.roomNotWaiting()
        }
        val rules = gameRegistry.rules(room.gameCode)
        val players = roomMemberRepository.findAll(roomId).filter { it.role == RoomRole.PLAYER }.map { it.userId }
        if (players.size !in rules.minPlayers..rules.maxPlayers) {
            throw RoomErrors.notEnoughPlayers(rules.minPlayers, rules.maxPlayers)
        }
        val seed = random.nextLong()
        val now = clock.instant()
        val session = roomSessionRepository.insert(
            roomId = roomId,
            gameCode = room.gameCode,
            stateSchemaVersion = rules.stateSchemaVersion,
            seed = seed,
            timeControl = room.timeControl,
            gameSettings = room.gameSettings,
            players = players,
            now = now,
        )
        val settings = jsonMapper.readValue(room.gameSettings, rules.settingsClass)
        val events = sessionEngine.start(rules, players, seed, settings, timeControl(room.timeControl), now)
        sessionJournalService.record(session, before = null, events, now)
        val updated = room.copy(status = RoomStatus.IN_GAME, currentSessionId = session.id)
        roomRepository.update(updated)
        roomEventPublisher.publish(roomId, RoomEventType.SESSION_CHANGED)
        return roomDetailsService.details(updated)
    }

    /**
     * Отмена: событие session-cancelled, комната возвращается в ожидание, отсутствующие игроки удаляются. Работает и
     * на паузе.
     */
    @Transactional
    fun cancel(userId: UUID, roomId: UUID) {
        val room = lockAsOwner(userId, roomId)
        val session = currentSession(room)
        sessionJournalService.cancel(session, clock.instant())
        endInRoom(room)
    }

    /**
     * Ставит таймер идущей партии на паузу: ходы запрещены, дедлайн хода заморожен (MoveTimeoutJob его не трогает). Только владелец. Повторный вызов на паузе ничего не меняет.
     */
    @Transactional
    fun pause(userId: UUID, roomId: UUID) {
        val session = lockRunningSessionAsOwner(userId, roomId)
        val now = clock.instant()
        val full = sessionJournalService.full(session)
        applyTimer(session, full, sessionEngine.pause(full, now))
    }

    /**
     * Снимает паузу: дедлайн хода сдвигается на длительность паузы, так что остаётся столько же времени, сколько было
     * в момент паузы. Только владелец. Без паузы ничего не меняет.
     */
    @Transactional
    fun resume(userId: UUID, roomId: UUID) {
        val session = lockRunningSessionAsOwner(userId, roomId)
        val now = clock.instant()
        val full = sessionJournalService.full(session)
        applyTimer(session, full, sessionEngine.resume(full, now))
    }

    /**
     * Ход — конверт `{type, data}`: вид действия и его параметры. Модуль игры получает его как свой класс хода
     * (GameRules.moveClass) и проверяет по текущему полному состоянию.
     */
    @Transactional
    fun move(userId: UUID, gameCode: GameCode, roomId: UUID, type: String, data: Map<String, Any?>?) {
        val room = lock(roomId)
        if (room.gameCode != gameCode) {
            throw RoomErrors.gameTypeMismatch()
        }
        val session = currentSession(room)
        if (roomMemberRepository.findAll(roomId).none { it.userId == userId }) {
            throw RoomErrors.notRoomMember()
        }
        if (userId !in roomSessionRepository.findPlayers(session.id)) {
            throw RoomErrors.notSessionPlayer()
        }
        val rules = gameRegistry.rules(session.gameCode)
        val rawMove = buildMap {
            put("type", type)
            data?.let { put("data", it) }
        }
        val move = try {
            jsonMapper.convertValue(rawMove, rules.moveClass)
        } catch (_: RuntimeException) {
            throw RoomErrors.invalidMove()
        }
        val now = clock.instant()
        val full = sessionJournalService.full(session)
        when (val outcome = sessionEngine.move(rules, full, userId, move, now)) {
            MoveOutcome.Paused -> throw RoomErrors.sessionPaused()
            MoveOutcome.NotInProgress -> throw RoomErrors.roomNotInGame()
            is MoveOutcome.Rejected -> throw RoomErrors.moveRejected(outcome.code, outcome.message)
            is MoveOutcome.Accepted -> afterChange(room, sessionJournalService.record(session, full, outcome.events, now))
        }
    }

    /**
     * Истекло время хода в партии [sessionId]: последствия решает игра ([SessionEngine.timeout]), дальше — как обычный
     * ход. Комната берётся под блокировку без ожидания: если её держит другой запрос, партия обработается в следующий
     * раз. Возвращает true, если время хода обработано.
     */
    @Transactional
    fun timeout(sessionId: UUID): Boolean {
        val now = clock.instant()
        val room = roomRepository.lockWithExpiredMove(sessionId, now) ?: return false
        // Перепроверка под блокировкой комнаты: ход, отмена или пауза могли успеть раньше.
        val session = roomSessionRepository.findById(sessionId) ?: return false
        val deadline = session.moveDeadline
        if (room.currentSessionId != sessionId || deadline == null || deadline > now) {
            return false
        }
        val full = sessionJournalService.full(session)
        val events = sessionEngine.timeout(gameRegistry.rules(session.gameCode), full, now)
        if (events.isEmpty()) {
            log.warn("Move deadline expired in session {}, but the game has nothing to do; timer is removed", sessionId)
            sessionJournalService.clearMoveDeadline(session)
            return false
        }
        afterChange(room, sessionJournalService.record(session, full, events, now))
        return true
    }

    private fun applyTimer(session: RoomSessionModel, full: SessionDocument, outcome: TimerOutcome) {
        when (outcome) {
            TimerOutcome.NotPausable -> throw RoomErrors.sessionNotPausable()
            TimerOutcome.Unchanged -> Unit
            is TimerOutcome.Changed -> sessionJournalService.record(session, full, outcome.events, clock.instant())
        }
    }

    /**
     * Партия закончилась — комната возвращается в ожидание, а партия остаётся видна как последняя. Закрепление игроков
     * партии снимается: отсутствующие удаляются из комнаты.
     */
    private fun afterChange(room: RoomModel, after: SessionDocument?) {
        if (after != null && after.session.status != SessionStatus.IN_PROGRESS) {
            endInRoom(room)
        }
    }

    private fun endInRoom(room: RoomModel) {
        val absent = roomMemberRepository.deleteAbsent(room.id)
        roomRepository.update(room.copy(status = RoomStatus.WAITING, currentSessionId = null))
        roomEventPublisher.publish(room.id, RoomEventType.SESSION_CHANGED)
        if (absent.isNotEmpty()) {
            roomEventPublisher.publish(room.id, RoomEventType.USERS_CHANGED)
        }
    }

    private fun currentSession(room: RoomModel): RoomSessionModel {
        val sessionId = room.currentSessionId ?: throw RoomErrors.roomNotInGame()
        return roomSessionRepository.findById(sessionId)!!
    }

    /** Идущая партия комнаты под блокировкой; вызывать может только участник-владелец. */
    private fun lockRunningSessionAsOwner(userId: UUID, roomId: UUID): RoomSessionModel {
        val room = lock(roomId)
        if (roomMemberRepository.findAll(roomId).none { it.userId == userId }) {
            throw RoomErrors.notRoomMember()
        }
        if (room.ownerId != userId) {
            throw RoomErrors.notRoomOwner()
        }
        return currentSession(room)
    }

    private fun lockAsOwner(userId: UUID, roomId: UUID): RoomModel {
        val room = lock(roomId)
        if (room.ownerId != userId) {
            throw RoomErrors.notRoomOwner()
        }
        return room
    }

    /** Комната под блокировкой. С комнатой принудительно отключённой игры работать нельзя (404 GAME_NOT_FOUND). */
    private fun lock(roomId: UUID): RoomModel {
        val room = roomRepository.lockById(roomId) ?: throw RoomErrors.roomNotFound()
        gameService.checkAccessible(room.gameCode)
        return room
    }

    companion object {
        private val log = LoggerFactory.getLogger(RoomSessionService::class.java)

        /** Правила времени комнаты → правила времени модуля игры (имена режимов совпадают). */
        fun timeControl(model: RoomTimeControlModel) = TimeControl(TimeControlMode.valueOf(model.mode.name), model.moveSeconds)
    }
}
