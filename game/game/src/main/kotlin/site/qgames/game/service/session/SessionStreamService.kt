package site.qgames.game.service.session

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus
import site.qgames.game.model.session.SessionEventsBatchModel
import site.qgames.game.model.session.SessionSnapshotModel
import site.qgames.game.model.session.SessionSubscriptionModel
import site.qgames.game.repository.room.RoomMemberRepository
import site.qgames.game.repository.room.RoomRepository
import site.qgames.game.repository.session.RoomSessionEventRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import site.qgames.game.service.game.GameService
import site.qgames.games.api.session.SessionEngine
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionStatus
import site.qgames.games.api.session.ViewerKey
import java.time.Clock
import java.util.UUID

/**
 * Поток партии: доступ, снапшот зрителя и события журнала для раздачи. Без блокировок: снапшот собирается по
 * закоммиченному журналу, а гонку с новыми событиями поток закрывает по seq.
 */
@Service
class SessionStreamService(
    private val roomRepository: RoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomSessionRepository: RoomSessionRepository,
    private val roomSessionEventRepository: RoomSessionEventRepository,
    private val sessionStateService: SessionStateService,
    private val gameService: GameService,
    private val gameRegistry: GameRegistry,
    private val sessionEngine: SessionEngine,
    private val clock: Clock,
) {

    /**
     * Подписаться может участник комнаты (в том числе отсутствующий игрок партии) на любую её партию. Проекция:
     * игрок партии — своя, остальные — наблюдательская; завершённая или отменённая партия — полная (всё публично).
     */
    @Transactional(readOnly = true)
    fun subscribe(userId: UUID, gameCode: GameCode, roomId: UUID, sessionId: UUID): SessionSubscriptionModel {
        val room = roomRepository.findById(roomId) ?: throw RoomErrors.roomNotFound()
        gameService.checkAccessible(room.gameCode)
        if (room.gameCode != gameCode) {
            throw RoomErrors.gameTypeMismatch()
        }
        if (roomMemberRepository.findAll(roomId).none { it.userId == userId }) {
            throw RoomErrors.notRoomMember()
        }
        val session = roomSessionRepository.findById(sessionId)?.takeIf { it.roomId == roomId } ?: throw RoomErrors.sessionNotFound()
        val viewer = when {
            session.status != RoomSessionStatus.IN_PROGRESS -> ViewerKey.Full
            userId in roomSessionRepository.findPlayers(sessionId) -> ViewerKey.Player(userId)
            else -> ViewerKey.Public
        }
        return SessionSubscriptionModel(session, viewer)
    }

    /**
     * Снапшот (документ партии) для [viewer] на последнем seq журнала. Если партия к этому моменту закончилась, снапшот
     * полный. null — партии уже нет (комната удалена).
     */
    @Transactional(readOnly = true)
    fun snapshot(sessionId: UUID, viewer: ViewerKey): SessionSnapshotModel? {
        val session = roomSessionRepository.findById(sessionId) ?: return null
        val state = sessionStateService.state(session, viewer)
        val result = if (state.document[SessionEngine.STATUS] != SessionStatus.IN_PROGRESS.name && viewer != ViewerKey.Full) {
            sessionStateService.state(session, ViewerKey.Full)
        } else {
            state
        }
        return SessionSnapshotModel(result, clock.instant())
    }

    /** События журнала [fromSeq]..[toSeq]; null — партии уже нет. */
    @Transactional(readOnly = true)
    fun events(sessionId: UUID, fromSeq: Long, toSeq: Long): SessionEventsBatchModel? {
        val session = roomSessionRepository.findById(sessionId) ?: return null
        return SessionEventsBatchModel(session, roomSessionEventRepository.findRange(sessionId, fromSeq, toSeq))
    }

    /** Событие для проекции [viewer] (маскируется только значение). */
    fun mask(session: RoomSessionModel, event: SessionEvent, viewer: ViewerKey): SessionEvent =
        sessionEngine.mask(gameRegistry.rules(session.gameCode), event, viewer)
}
