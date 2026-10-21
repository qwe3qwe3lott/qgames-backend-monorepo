package site.qgames.game.publisher.session

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Публикует в шину между инстансами game (`pg_notify('session_events', …)`), что в журнал партии дописаны события
 * fromSeq..toSeq. Вызывается в транзакции записи: PostgreSQL доставит уведомление только после коммита и в порядке
 * коммитов. Данных событий в уведомлении нет: инстанс-получатель читает их из журнала, если у него есть подписчики.
 */
@Component
class SessionEventPublisher(
    private val jdbcClient: JdbcClient,
    private val jsonMapper: JsonMapper,
) {

    fun publish(sessionId: UUID, fromSeq: Long, toSeq: Long) {
        val payload = jsonMapper.writeValueAsString(mapOf(SESSION_ID to sessionId.toString(), FROM_SEQ to fromSeq, TO_SEQ to toSeq))
        jdbcClient.sql("SELECT pg_notify(:channel, :payload)")
            .param("channel", CHANNEL)
            .param("payload", payload)
            .query { _, _ -> }
            .list()
    }

    companion object {
        const val CHANNEL = "session_events"
        const val SESSION_ID = "sessionId"
        const val FROM_SEQ = "fromSeq"
        const val TO_SEQ = "toSeq"
    }
}
