package site.qgames.game.publisher.session

import org.springframework.stereotype.Component
import site.qgames.game.publisher.common.PgNotifier
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Публикует (`pg_notify('session_events', …)`, [PgNotifier]), что в журнал партии дописаны события fromSeq..toSeq.
 * Вызывается в транзакции записи: PostgreSQL доставит уведомление только после коммита и в порядке коммитов. Данных
 * событий в уведомлении нет: получатель читает их из журнала один раз, если у партии есть подписчики.
 */
@Component
class SessionEventPublisher(
    private val pgNotifier: PgNotifier,
    private val jsonMapper: JsonMapper,
) {

    fun publish(sessionId: UUID, fromSeq: Long, toSeq: Long) {
        val payload = jsonMapper.writeValueAsString(mapOf(SESSION_ID to sessionId.toString(), FROM_SEQ to fromSeq, TO_SEQ to toSeq))
        pgNotifier.notify(CHANNEL, payload)
    }

    companion object {
        const val CHANNEL = "session_events"
        const val SESSION_ID = "sessionId"
        const val FROM_SEQ = "fromSeq"
        const val TO_SEQ = "toSeq"
    }
}
