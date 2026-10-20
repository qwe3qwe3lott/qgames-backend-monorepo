package site.qgames.game.service.session

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomRole
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.model.room.RoomTimeControlMode
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionMoveModel
import site.qgames.game.model.session.RoomSessionStatus
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.room.RoomRepository
import site.qgames.game.repository.session.RoomSessionMoveRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import site.qgames.game.service.game.GameService
import site.qgames.game.service.room.RoomDetailsService
import site.qgames.games.api.GameRules
import site.qgames.games.api.MoveResult
import tools.jackson.databind.json.JsonMapper
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Партии в комнате. Правила конкретной игры берутся из модуля игры через [GameRegistry].
 * Все операции идут под блокировкой строки комнаты, ход дополнительно защищён версией партии.
 * Таймер хода — колонка room_session.move_deadline: её ставит старт, переставляет каждый ход (в том числе
 * истечение времени), снимают конец и отмена партии. Пауза (room_session.paused_at) запрещает ходы и замораживает
 * таймер хода; отменить партию на паузе можно.
 */
@Service
class RoomSessionService(
    private val roomRepository: RoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomSessionRepository: RoomSessionRepository,
    private val roomSessionMoveRepository: RoomSessionMoveRepository,
    private val gameRegistry: GameRegistry,
    private val gameService: GameService,
    private val roomDetailsService: RoomDetailsService,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    private val random = SecureRandom()

    /**
     * Игроки — участники с ролью PLAYER, места — по порядку входа в комнату. Партия получает снимок настроек
     * комнаты; при таймере на ход сразу ставится дедлайн первого хода. Игра должна быть доступна для новых партий.
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
        val settings = jsonMapper.readValue(room.gameSettings, rules.settingsClass)
        val session = roomSessionRepository.insert(
            roomId = roomId,
            gameCode = room.gameCode,
            state = jsonMapper.writeValueAsString(rules.initialState(players, seed, settings)),
            stateSchemaVersion = rules.stateSchemaVersion,
            seed = seed,
            timeControl = room.timeControl,
            gameSettings = room.gameSettings,
            moveDeadline = moveDeadline(room.timeControl, now),
            players = players,
            now = now,
        )
        val updated = room.copy(status = RoomStatus.IN_GAME, currentSessionId = session.id)
        roomRepository.update(updated)
        return roomDetailsService.details(updated, userId)
    }

    @Transactional
    fun cancel(userId: UUID, roomId: UUID): RoomDetailsModel {
        val room = lockAsOwner(userId, roomId)
        val sessionId = room.currentSessionId ?: throw RoomErrors.roomNotInGame()
        roomSessionRepository.finish(sessionId, RoomSessionStatus.CANCELLED, clock.instant())
        val updated = room.copy(status = RoomStatus.WAITING, currentSessionId = null)
        roomRepository.update(updated)
        return roomDetailsService.details(updated, userId)
    }

    /**
     * Ставит идущую партию на паузу: ходы запрещены, таймер хода и сроки возвращения игроков партии заморожены
     * (джобы их не трогают). Только владелец. Повторный вызов на паузе ничего не меняет.
     */
    @Transactional
    fun pause(userId: UUID, roomId: UUID): RoomDetailsModel {
        val (room, session) = lockRunningSessionAsOwner(userId, roomId)
        if (session.pausedAt == null) {
            roomSessionRepository.pause(session.id, clock.instant())
        }
        return roomDetailsService.details(room, userId)
    }

    /**
     * Снимает паузу: дедлайн хода и сроки возвращения вышедших игроков партии сдвигаются на длительность паузы,
     * так что у всех остаётся столько же времени, сколько было в момент паузы. Только владелец. Без паузы ничего не меняет.
     */
    @Transactional
    fun resume(userId: UUID, roomId: UUID): RoomDetailsModel {
        val (room, session) = lockRunningSessionAsOwner(userId, roomId)
        val pausedAt = session.pausedAt
        if (pausedAt != null) {
            val shift = Duration.between(pausedAt, clock.instant()).coerceAtLeast(Duration.ZERO)
            roomSessionRepository.resume(session.id, shift)
            roomMemberRepository.shiftLeaveDeadlines(roomId, roomSessionRepository.findPlayers(session.id), shift)
        }
        return roomDetailsService.details(room, userId)
    }

    /**
     * Ход — конверт `{type, data}`: вид действия и его параметры. Модуль игры получает его как свой класс хода
     * ([GameRules.moveClass]); тот же конверт пишется в журнал ходов.
     */
    @Transactional
    fun move(
        userId: UUID,
        gameCode: GameCode,
        roomId: UUID,
        type: String,
        data: Map<String, Any?>?,
        expectedVersion: Long?,
    ): RoomDetailsModel {
        val room = lock(roomId)
        if (room.gameCode != gameCode) {
            throw RoomErrors.gameTypeMismatch()
        }
        val rawMove = buildMap {
            put("type", type)
            data?.let { put("data", it) }
        }
        val sessionId = room.currentSessionId ?: throw RoomErrors.roomNotInGame()
        if (roomMemberRepository.findAll(roomId).none { it.userId == userId }) {
            throw RoomErrors.notRoomMember()
        }
        if (userId !in roomSessionRepository.findPlayers(sessionId)) {
            throw RoomErrors.notSessionPlayer()
        }
        val session = roomSessionRepository.findById(sessionId)!!
        if (session.pausedAt != null) {
            throw RoomErrors.sessionPaused()
        }
        if (expectedVersion != null && expectedVersion != session.version) {
            throw RoomErrors.sessionConflict()
        }

        val rules = gameRegistry.rules(session.gameCode)
        val move = try {
            jsonMapper.convertValue(rawMove, rules.moveClass)
        } catch (_: RuntimeException) {
            throw RoomErrors.invalidMove()
        }
        val state = jsonMapper.readValue(session.state, rules.stateClass)
        val newState = when (val result = rules.applyMove(state, userId, move)) {
            is MoveResult.Rejected -> throw RoomErrors.moveRejected(result.code, result.message)
            is MoveResult.Applied -> result.state
        }
        val updated = saveTransition(room, session, rules, newState, userId, rawMove)
        return roomDetailsService.details(updated, userId)
    }

    /**
     * Истекло время хода в партии [sessionId]: последствия решает игра ([GameRules.onMoveTimeout]), дальше — как
     * обычный ход: версия + 1, запись `{type: "TIMEOUT"}` от игрока, чьё время истекло, новый дедлайн, конец партии.
     * Комната берётся под блокировку без ожидания: если её держит другой запрос, партия обработается в следующий раз.
     * Возвращает true, если время хода обработано.
     */
    @Transactional
    fun timeout(sessionId: UUID): Boolean {
        val now = clock.instant()
        val room = roomRepository.lockWithExpiredMove(sessionId, now) ?: return false
        // Перепроверка под блокировкой комнаты: ход, отмена или пауза могли успеть раньше.
        val session = roomSessionRepository.findById(sessionId) ?: return false
        val deadline = session.moveDeadline
        if (
            room.currentSessionId != sessionId ||
            session.status != RoomSessionStatus.IN_PROGRESS ||
            session.pausedAt != null ||
            deadline == null ||
            deadline > now
        ) {
            return false
        }
        val rules = gameRegistry.rules(session.gameCode)
        val state = jsonMapper.readValue(session.state, rules.stateClass)
        val player = rules.playerToMove(state)
        if (player == null) {
            log.warn("Move deadline expired in session {}, but the game has nobody to move; timer is removed", sessionId)
            roomSessionRepository.clearMoveDeadline(sessionId)
            return false
        }
        saveTransition(room, session, rules, rules.onMoveTimeout(state), player, mapOf("type" to TIMEOUT), now)
        return true
    }

    /**
     * Сохраняет новое состояние партии под оптимистичной блокировкой версии и пишет конверт хода в журнал.
     * Если партия закончилась, комната возвращается в ожидание, а партия остаётся видна как последняя.
     */
    private fun saveTransition(
        room: RoomModel,
        session: RoomSessionModel,
        rules: GameRules<Any, Any, Any>,
        newState: Any,
        player: UUID,
        rawMove: Map<String, Any?>,
        now: Instant = clock.instant(),
    ): RoomModel {
        val finished = rules.isFinished(newState)
        val saved = roomSessionRepository.updateState(
            id = session.id,
            expectedVersion = session.version,
            state = jsonMapper.writeValueAsString(newState),
            status = if (finished) RoomSessionStatus.FINISHED else RoomSessionStatus.IN_PROGRESS,
            moveDeadline = if (finished) null else moveDeadline(session.timeControl, now),
            finishedAt = if (finished) now else null,
        )
        if (!saved) {
            throw RoomErrors.sessionConflict()
        }
        roomSessionMoveRepository.insert(
            RoomSessionMoveModel(session.id, session.version + 1, player, jsonMapper.writeValueAsString(rawMove), now),
        )
        if (!finished) {
            return room
        }
        val updated = room.copy(status = RoomStatus.WAITING, currentSessionId = null)
        roomRepository.update(updated)
        return updated
    }

    /** Дедлайн следующего хода по правилам времени партии; null — без таймера. */
    private fun moveDeadline(timeControl: RoomTimeControlModel, now: Instant): Instant? = when (timeControl.mode) {
        RoomTimeControlMode.NONE -> null
        RoomTimeControlMode.PER_MOVE -> now.plusSeconds(timeControl.moveSeconds!!.toLong())
    }

    /** Комната под блокировкой и её идущая партия; вызывать может только участник-владелец. */
    private fun lockRunningSessionAsOwner(userId: UUID, roomId: UUID): Pair<RoomModel, RoomSessionModel> {
        val room = lock(roomId)
        if (roomMemberRepository.findAll(roomId).none { it.userId == userId }) {
            throw RoomErrors.notRoomMember()
        }
        if (room.ownerId != userId) {
            throw RoomErrors.notRoomOwner()
        }
        val sessionId = room.currentSessionId ?: throw RoomErrors.roomNotInGame()
        return room to roomSessionRepository.findById(sessionId)!!
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
        /** Вид записи в журнале ходов, когда время хода истекло. */
        const val TIMEOUT = "TIMEOUT"

        private val log = LoggerFactory.getLogger(RoomSessionService::class.java)
    }
}
