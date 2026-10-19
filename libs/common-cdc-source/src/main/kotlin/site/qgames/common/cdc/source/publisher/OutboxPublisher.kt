package site.qgames.common.cdc.source.publisher

import com.google.protobuf.MessageLite
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import site.qgames.common.cdc.source.config.OutboxProperties
import java.util.UUID

/**
 * Transactional Outbox: событие пишется в `outbox_event` в той же транзакции, что и бизнес-изменение.
 * Debezium читает INSERT из WAL и публикует payload в топик `outbox.event.<aggregate_type>` с ключом aggregate_id.
 *
 * Строка сразу удаляется (если не выключено [OutboxProperties.deleteAfterInsert]): в WAL INSERT уже есть,
 * а DELETE EventRouter игнорирует — таблица не растёт.
 */
open class OutboxPublisher(
    private val jdbcClient: JdbcClient,
    private val properties: OutboxProperties,
) {

    @Transactional(propagation = Propagation.MANDATORY)
    open fun publish(aggregateType: String, aggregateId: String, eventType: String, payload: MessageLite) {
        val id = jdbcClient.sql(
            """
            INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
            VALUES (:aggregateType, :aggregateId, :eventType, :payload)
            RETURNING id
            """,
        )
            .param("aggregateType", aggregateType)
            .param("aggregateId", aggregateId)
            .param("eventType", eventType)
            .param("payload", payload.toByteArray())
            .query(UUID::class.java)
            .single()
        if (properties.deleteAfterInsert) {
            jdbcClient.sql("DELETE FROM outbox_event WHERE id = :id").param("id", id).update()
        }
    }
}
