package site.qgames.game.stream.room

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import site.qgames.game.mapper.room.RoomEventResponseMapper
import site.qgames.game.model.room.RoomEventModel
import site.qgames.game.stream.session.SessionStreamRegistry

/**
 * Раздаёт событие комнаты (NOTIFY) подписчикам её потока. Событие приходит целиком, с seq и данными на момент изменения,
 * поэтому в БД диспетчер не ходит. Последнее событие подписчика (комната удалена, он исключён) закрывает его поток.
 * Участник ушёл или исключён — закрываются и его потоки партий комнаты: право на них было только у участника.
 */
@Component
class RoomStreamDispatcher(
    private val registry: RoomStreamRegistry,
    private val sessionRegistry: SessionStreamRegistry,
    private val roomEventResponseMapper: RoomEventResponseMapper,
) {

    fun dispatch(model: RoomEventModel) {
        try {
            roomEventResponseMapper.removedUserId(model.event)?.let { sessionRegistry.close(model.roomId, it) }
            val subscribers = registry.subscribers(model.roomId)
            if (subscribers.isEmpty()) return
            val type = roomEventResponseMapper.toType(model.event)
            val frame = roomEventResponseMapper.toFrame(model)
            subscribers.forEach { it.event(model.seq, type, frame, roomEventResponseMapper.isLast(model.event, it.userId)) }
        } catch (e: RuntimeException) {
            log.error("Failed to dispatch room event {}", model, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RoomStreamDispatcher::class.java)
    }
}
