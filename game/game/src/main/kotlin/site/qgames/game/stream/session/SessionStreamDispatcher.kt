package site.qgames.game.stream.session

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import site.qgames.game.mapper.session.SessionEventResponseMapper
import site.qgames.game.model.session.SessionEventsNotificationModel
import site.qgames.game.service.session.SessionStreamService
import site.qgames.games.api.session.SessionEvents

/**
 * Раздаёт события журнала партии подписчикам её потока. События `fromSeq..toSeq` читаются из журнала один раз (уже
 * после коммита), маскируются один раз на проекцию и уходят каждому подписчику со своим seq.
 *
 * Если объявленных событий в журнале нет (партию удалили вместе с комнатой), потоки закрываются: клиенты
 * переподключатся и получат ошибку.
 */
@Component
class SessionStreamDispatcher(
    private val registry: SessionStreamRegistry,
    private val sessionStreamService: SessionStreamService,
    private val sessionEventResponseMapper: SessionEventResponseMapper,
) {

    fun dispatch(notification: SessionEventsNotificationModel) {
        val subscribers = registry.subscribers(notification.sessionId)
        if (subscribers.isEmpty()) return
        try {
            val batch = sessionStreamService.events(notification.sessionId, notification.fromSeq, notification.toSeq)
            if (batch == null || batch.events.size.toLong() != notification.toSeq - notification.fromSeq + 1) {
                log.info("Events {} are not in the journal anymore, closing the streams", notification)
                subscribers.forEach(SessionStreamSubscriber::close)
                return
            }
            subscribers.groupBy { it.viewer }.forEach { (viewer, group) ->
                batch.events.forEach { stored ->
                    val event = sessionStreamService.mask(batch.session, stored.event, viewer)
                    val frame = sessionEventResponseMapper.toFrame(stored.seq, stored.createdAt, event)
                    val last = event.code in SessionEvents.ENDING
                    val type = sessionEventResponseMapper.toType(event)
                    group.forEach { it.event(stored.seq, type, frame, last) }
                }
            }
        } catch (e: RuntimeException) {
            // Без события поток разошёлся бы с журналом: клиент переподключится и получит свежий снапшот.
            log.error("Failed to dispatch session events {}, closing the streams", notification, e)
            subscribers.forEach(SessionStreamSubscriber::close)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SessionStreamDispatcher::class.java)
    }
}
