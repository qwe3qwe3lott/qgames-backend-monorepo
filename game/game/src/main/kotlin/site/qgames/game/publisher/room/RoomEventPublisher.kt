package site.qgames.game.publisher.room

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.mapper.patch.PatchJsonMapper
import site.qgames.game.mapper.room.RoomEventResponseMapper
import site.qgames.game.model.room.RoomEvent
import site.qgames.game.model.room.RoomMemberDetailsModel
import site.qgames.game.model.room.RoomRole
import site.qgames.game.model.room.RoomSettingsModel
import site.qgames.game.model.session.RoomSessionModel
import site.qgames.game.publisher.common.PgNotifier
import site.qgames.game.repository.room.RoomRepository
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/**
 * Публикует события потока комнаты — патчи документа потока (комнаты): `pg_notify('room_events', …)` ([PgNotifier]).
 * Вызывается в транзакции изменения под блокировкой комнаты: seq события — следующий `room.event_seq`, данные события
 * фиксируются здесь же. PostgreSQL доставляет уведомления только после коммита (при откате — никогда) и в порядке
 * коммитов, поэтому outbox не нужен. Уведомление несёт событие целиком, его принимает RoomEventListener.
 *
 * Одно событие — одна операция; составное изменение публикуется несколькими событиями подряд.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class RoomEventPublisher(
    private val pgNotifier: PgNotifier,
    private val roomRepository: RoomRepository,
    private val roomEventResponseMapper: RoomEventResponseMapper,
    private val patchJsonMapper: PatchJsonMapper,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    fun memberJoined(roomId: UUID, member: RoomMemberDetailsModel) = publish(roomId, roomEventResponseMapper.memberJoined(member))

    fun memberLeft(roomId: UUID, userId: UUID) = publish(roomId, roomEventResponseMapper.memberLeft(userId))

    fun memberLeaving(roomId: UUID, userId: UUID) = publish(roomId, roomEventResponseMapper.memberLeaving(userId))

    fun memberReturned(roomId: UUID, userId: UUID) = publish(roomId, roomEventResponseMapper.memberReturned(userId))

    /** Перед выходом исключённого (member-left или member-leaving); у исключённого — последнее событие потока. */
    fun memberKicked(roomId: UUID, userId: UUID) = publish(roomId, roomEventResponseMapper.memberKicked(userId))

    fun roleChanged(roomId: UUID, userId: UUID, role: RoomRole) = publish(roomId, roomEventResponseMapper.roleChanged(userId, role))

    fun profileChanged(roomId: UUID, member: RoomMemberDetailsModel) = publish(roomId, roomEventResponseMapper.profileChanged(member))

    fun ownerChanged(roomId: UUID, ownerId: UUID?) = publish(roomId, roomEventResponseMapper.ownerChanged(ownerId))

    fun settingsChanged(roomId: UUID, settings: RoomSettingsModel) =
        publish(roomId, roomEventResponseMapper.settingsChanged(settings))

    fun sessionStarted(roomId: UUID, session: RoomSessionModel) = publish(roomId, roomEventResponseMapper.sessionStarted(session))

    fun sessionEnded(roomId: UUID, session: RoomSessionModel) = publish(roomId, roomEventResponseMapper.sessionEnded(session))

    /** Последнее событие потока; вызывается до удаления строки комнаты (seq берётся из неё). */
    fun roomDeleted(roomId: UUID) = publish(roomId, roomEventResponseMapper.roomDeleted())

    private fun publish(roomId: UUID, event: RoomEvent) {
        val payload = jsonMapper.writeValueAsString(
            linkedMapOf(
                ROOM_ID to roomId.toString(),
                SEQ to roomRepository.nextEventSeq(roomId),
                AT to clock.instant(),
                CODE to event.code,
                METHOD to event.method.name,
                DATA to patchJsonMapper.data(event),
            ),
        )
        // Больше лимита NOTIFY PostgreSQL pg_notify откажет сам, но без понятной причины.
        check(payload.toByteArray().size < MAX_PAYLOAD_BYTES) { "Room event ${event.code} is too large for NOTIFY: ${payload.length} chars" }
        pgNotifier.notify(CHANNEL, payload)
    }

    companion object {
        const val CHANNEL = "room_events"
        const val ROOM_ID = "roomId"
        const val SEQ = "seq"
        const val AT = "at"
        const val CODE = "code"
        const val METHOD = "method"
        const val DATA = "data"

        /** Лимит payload `pg_notify` — 8000 байт. */
        private const val MAX_PAYLOAD_BYTES = 8000
    }
}
