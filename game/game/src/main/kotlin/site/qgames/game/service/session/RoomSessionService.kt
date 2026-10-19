package site.qgames.game.service.session

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.model.room.RoomModel
import site.qgames.game.model.room.RoomRole
import site.qgames.game.model.room.RoomStatus
import site.qgames.game.model.session.RoomSessionMoveModel
import site.qgames.game.model.session.RoomSessionStatus
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.room.RoomDetailsModel
import site.qgames.game.model.room.RoomGameType
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.room.RoomRepository
import site.qgames.game.repository.session.RoomSessionMoveRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import site.qgames.game.service.room.RoomDetailsService
import site.qgames.games.api.MoveResult
import tools.jackson.databind.json.JsonMapper
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Партии в комнате. Правила конкретной игры берутся из модуля games/<игра> через [GameRegistry].
 * Все операции идут под блокировкой строки комнаты, ход дополнительно защищён версией партии.
 */
@Service
class RoomSessionService(
    private val roomRepository: RoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomSessionRepository: RoomSessionRepository,
    private val roomSessionMoveRepository: RoomSessionMoveRepository,
    private val gameRegistry: GameRegistry,
    private val roomDetailsService: RoomDetailsService,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    private val random = SecureRandom()

    /** Игроки — участники с ролью PLAYER, места — по порядку входа в комнату. */
    @Transactional
    fun start(userId: UUID, roomId: UUID): RoomDetailsModel {
        val room = lockAsOwner(userId, roomId)
        if (room.status != RoomStatus.WAITING) {
            throw RoomErrors.roomNotWaiting()
        }
        val rules = gameRegistry.rules(room.gameType)
        val players = roomMemberRepository.findAll(roomId).filter { it.role == RoomRole.PLAYER }.map { it.userId }
        if (players.size !in rules.minPlayers..rules.maxPlayers) {
            throw RoomErrors.notEnoughPlayers(rules.minPlayers, rules.maxPlayers)
        }
        val seed = random.nextLong()
        val session = roomSessionRepository.insert(
            roomId = roomId,
            gameType = room.gameType,
            state = jsonMapper.writeValueAsString(rules.initialState(players, seed)),
            stateSchemaVersion = rules.stateSchemaVersion,
            seed = seed,
            players = players,
            now = clock.instant(),
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
     * Ход — конверт `{type, data}`: вид действия и его параметры. Модуль игры получает его как свой класс хода
     * ([site.qgames.games.api.GameRules.moveClass]); тот же конверт пишется в журнал ходов.
     */
    @Transactional
    fun move(
        userId: UUID,
        gameType: RoomGameType,
        roomId: UUID,
        type: String,
        data: Map<String, Any?>?,
        expectedVersion: Long?,
    ): RoomDetailsModel {
        val room = roomRepository.lockById(roomId) ?: throw RoomErrors.roomNotFound()
        if (room.gameType != gameType) {
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
        if (expectedVersion != null && expectedVersion != session.version) {
            throw RoomErrors.sessionConflict()
        }

        val rules = gameRegistry.rules(session.gameType)
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

        val now = clock.instant()
        val finished = rules.isFinished(newState)
        val saved = roomSessionRepository.updateState(
            id = sessionId,
            expectedVersion = session.version,
            state = jsonMapper.writeValueAsString(newState),
            status = if (finished) RoomSessionStatus.FINISHED else RoomSessionStatus.IN_PROGRESS,
            finishedAt = if (finished) now else null,
        )
        if (!saved) {
            throw RoomErrors.sessionConflict()
        }
        roomSessionMoveRepository.insert(RoomSessionMoveModel(sessionId, session.version + 1, userId, jsonMapper.writeValueAsString(rawMove), now))

        // Законченная партия остаётся видна в ответе как последняя партия комнаты.
        val updated = if (finished) room.copy(status = RoomStatus.WAITING, currentSessionId = null) else room
        if (finished) {
            roomRepository.update(updated)
        }
        return roomDetailsService.details(updated, userId)
    }

    private fun lockAsOwner(userId: UUID, roomId: UUID): RoomModel {
        val room = roomRepository.lockById(roomId) ?: throw RoomErrors.roomNotFound()
        if (room.ownerId != userId) {
            throw RoomErrors.notRoomOwner()
        }
        return room
    }
}
