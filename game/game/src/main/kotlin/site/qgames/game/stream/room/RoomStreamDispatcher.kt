package site.qgames.game.stream.room

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import site.qgames.game.mapper.room.RoomEventResponseMapper
import site.qgames.game.model.room.RoomEventModel

/**
 * Раздаёт событие комнаты (NOTIFY) подписчикам её потока. Событие приходит целиком, с seq и данными на момент изменения,
 * поэтому в БД диспетчер не ходит. Последнее событие (комната удалена) закрывает потоки.
 */
@Component
class RoomStreamDispatcher(
    private val registry: RoomStreamRegistry,
    private val roomEventResponseMapper: RoomEventResponseMapper,
) {

    fun dispatch(model: RoomEventModel) {
        val subscribers = registry.subscribers(model.roomId)
        if (subscribers.isEmpty()) return
        try {
            val type = roomEventResponseMapper.toType(model.event)
            val frame = roomEventResponseMapper.toFrame(model)
            val last = roomEventResponseMapper.isLast(model.event)
            subscribers.forEach { it.event(model.seq, type, frame, last) }
        } catch (e: RuntimeException) {
            log.error("Failed to dispatch room event {}", model, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RoomStreamDispatcher::class.java)
    }
}
