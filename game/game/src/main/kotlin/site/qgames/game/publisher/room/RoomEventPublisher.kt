package site.qgames.game.publisher.room

import org.springframework.stereotype.Component
import site.qgames.game.model.room.RoomEventModel
import site.qgames.game.model.room.RoomEventType
import site.qgames.game.publisher.common.PgNotifier
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Публикует события комнаты: `pg_notify('room_events', …)` ([PgNotifier]). Вызывается в транзакции изменения:
 * PostgreSQL доставляет уведомления только после коммита (при откате — никогда) и в порядке коммитов, поэтому outbox
 * не нужен. Уведомление — маленький внутренний JSON (без данных), его принимает RoomEventListener.
 */
@Component
class RoomEventPublisher(
    private val pgNotifier: PgNotifier,
    private val jsonMapper: JsonMapper,
) {

    fun publish(roomId: UUID, type: RoomEventType) = notify(RoomEventModel(roomId, type))

    /** Маркер подключения подписчика [subscriberId] (см. [RoomEventType.SUBSCRIBED]). */
    fun publishSubscribed(roomId: UUID, subscriberId: UUID) =
        notify(RoomEventModel(roomId, RoomEventType.SUBSCRIBED, subscriberId))

    private fun notify(event: RoomEventModel) {
        val payload = jsonMapper.writeValueAsString(
            buildMap {
                put(ROOM_ID, event.roomId.toString())
                put(TYPE, event.type.code)
                event.subscriberId?.let { put(SUBSCRIBER_ID, it.toString()) }
            },
        )
        pgNotifier.notify(CHANNEL, payload)
    }

    companion object {
        const val CHANNEL = "room_events"
        const val ROOM_ID = "roomId"
        const val TYPE = "type"
        const val SUBSCRIBER_ID = "subscriberId"
    }
}
