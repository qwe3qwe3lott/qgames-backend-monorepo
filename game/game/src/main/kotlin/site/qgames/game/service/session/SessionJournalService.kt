package site.qgames.game.service.session

import org.springframework.stereotype.Service
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.model.session.RoomSessionStatus
import site.qgames.game.model.session.SessionStateModel
import site.qgames.game.publisher.session.SessionEventPublisher
import site.qgames.game.repository.session.RoomSessionEventRepository
import site.qgames.game.repository.session.RoomSessionRepository
import site.qgames.game.service.game.GameRegistry
import site.qgames.games.api.session.Session
import site.qgames.games.api.session.SessionEngine
import site.qgames.games.api.session.SessionEvent
import site.qgames.games.api.session.SessionStatus
import site.qgames.games.api.session.ViewerKey
import site.qgames.games.api.timer.TimerModule
import java.time.Instant

/**
 * Запись изменений партии в журнал. Вызывается под блокировкой комнаты: полное состояние на last_seq
 * ([full]) → события изменения → [record]: события с seq от last_seq + 1, новый last_seq, статус и проекция таймера
 * (move_deadline) в room_session, уведомление `session_events` (доставится после коммита).
 */
@Service
class SessionJournalService(
    private val roomSessionRepository: RoomSessionRepository,
    private val roomSessionEventRepository: RoomSessionEventRepository,
    private val sessionStateService: SessionStateService,
    private val sessionEventPublisher: SessionEventPublisher,
    private val gameRegistry: GameRegistry,
) {

    /** Полное состояние партии на её last_seq. */
    fun full(session: RoomSessionModel): Session = sessionStateService.state(session, ViewerKey.Full).session

    /**
     * Дописывает [events] к партии [session] с полным состоянием [before] (null — журнал пуст) и возвращает полное
     * состояние после них. Пустой список ничего не меняет.
     */
    fun record(session: RoomSessionModel, before: Session?, events: List<SessionEvent>, now: Instant): Session? {
        if (events.isEmpty()) return before
        val rules = gameRegistry.rules(session.gameCode)
        val fromSeq = session.lastSeq + 1
        val toSeq = session.lastSeq + events.size
        roomSessionEventRepository.append(session.id, fromSeq, events, now)
        val after = SessionEngine.fold(rules, before, events)!!
        val status = RoomSessionStatus.valueOf(after.status.name)
        val inProgress = after.status == SessionStatus.IN_PROGRESS
        roomSessionRepository.advance(
            id = session.id,
            lastSeq = toSeq,
            status = status,
            moveDeadline = if (inProgress) TimerModule.runningDeadline(after.state.timer) else null,
            finishedAt = if (inProgress) null else now,
        )
        sessionEventPublisher.publish(session.id, fromSeq, toSeq)
        sessionStateService.cacheAfterCommit(session.id, ViewerKey.Full, SessionStateModel(toSeq, after))
        return after
    }

    /** Время хода истекло, но игре нечего делать: проекция таймера снимается, чтобы джоб не брал партию снова. */
    fun clearMoveDeadline(session: RoomSessionModel) =
        roomSessionRepository.advance(session.id, session.lastSeq, session.status, moveDeadline = null, finishedAt = session.finishedAt)
}
