package site.qgames.game.service.session

import org.springframework.stereotype.Service
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus
import site.qgames.game.model.session.SessionStateModel
import site.qgames.game.publisher.session.SessionEventPublisher
import site.qgames.game.repository.session.RoomSessionEventRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import site.qgames.games.api.session.SessionDocument
import site.qgames.games.api.session.SessionEngine
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionStatus
import site.qgames.games.api.session.ViewerKey
import site.qgames.games.api.timer.TimerModule
import java.time.Instant

/**
 * Запись изменений партии в журнал. Вызывается под блокировкой комнаты: полный документ на last_seq ([full]) →
 * события изменения → [record]: события с seq от last_seq + 1, новый last_seq, статус и проекция таймера
 * (move_deadline) в room_session, уведомление `session_events` (доставится после коммита).
 */
@Service
class SessionJournalService(
    private val roomSessionRepository: RoomSessionRepository,
    private val roomSessionEventRepository: RoomSessionEventRepository,
    private val sessionStateService: SessionStateService,
    private val sessionEventPublisher: SessionEventPublisher,
    private val gameRegistry: GameRegistry,
    private val sessionEngine: SessionEngine,
) {

    /** Полный документ партии на её last_seq и его типизированное чтение. */
    fun full(session: RoomSessionModel): SessionDocument =
        sessionEngine.read(gameRegistry.rules(session.gameCode), sessionStateService.state(session, ViewerKey.Full).document)

    /**
     * Дописывает [events] к партии [session] с полным документом [before] (null — журнал пуст) и возвращает полный
     * документ после них. Пустой список ничего не меняет.
     */
    fun record(session: RoomSessionModel, before: SessionDocument?, events: List<SessionEvent>, now: Instant): SessionDocument? {
        if (events.isEmpty()) return before
        val fromSeq = session.lastSeq + 1
        val toSeq = session.lastSeq + events.size
        roomSessionEventRepository.append(session.id, fromSeq, events, now)
        val tree = SessionEngine.fold(before?.tree, events)!!
        val after = sessionEngine.read(gameRegistry.rules(session.gameCode), tree)
        val status = after.session.status
        val inProgress = status == SessionStatus.IN_PROGRESS
        roomSessionRepository.advance(
            id = session.id,
            lastSeq = toSeq,
            status = RoomSessionStatus.valueOf(status.name),
            moveDeadline = if (inProgress) TimerModule.runningDeadline(after.session.state.timer) else null,
            finishedAt = if (inProgress) null else now,
        )
        sessionEventPublisher.publish(session.id, fromSeq, toSeq)
        sessionStateService.cacheAfterCommit(session.id, ViewerKey.Full, SessionStateModel(toSeq, tree))
        return after
    }

    /** Отмена идущей партии ([SessionEngine.cancel]); возвращает полный документ после неё. */
    fun cancel(session: RoomSessionModel, now: Instant): SessionDocument? {
        val full = full(session)
        return record(session, full, sessionEngine.cancel(full, now), now)
    }

    /** Время хода истекло, но игре нечего делать: проекция таймера снимается, чтобы джоб не брал партию снова. */
    fun clearMoveDeadline(session: RoomSessionModel) =
        roomSessionRepository.advance(session.id, session.lastSeq, session.status, moveDeadline = null, finishedAt = session.finishedAt)
}
