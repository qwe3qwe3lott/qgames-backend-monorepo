package site.qgames.game.stream.room

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import site.qgames.game.api.dto.room.RoomStreamEventType
import site.qgames.game.mapper.room.RoomEventResponseMapper
import site.qgames.game.model.room.RoomEventModel
import site.qgames.game.model.room.RoomEventType
import site.qgames.game.service.room.RoomService

/**
 * Раздаёт событие комнаты из шины локальным подписчикам её потока. Данные события (участники, настройки) читаются
 * здесь один раз на инстанс, уже после коммита изменения, — поэтому они не старше события. События несут полное
 * значение, так что более свежие данные безопасны.
 */
@Component
class RoomStreamDispatcher(
    private val registry: RoomStreamRegistry,
    private val roomService: RoomService,
    private val roomEventResponseMapper: RoomEventResponseMapper,
) {

    fun dispatch(event: RoomEventModel) {
        val subscribers = registry.subscribers(event.roomId)
        if (subscribers.isEmpty()) return
        try {
            when (event.type) {
                RoomEventType.SUBSCRIBED -> subscribers.firstOrNull { it.id == event.subscriberId }?.markerReceived()
                RoomEventType.USERS_CHANGED -> roomService.findUsers(event.roomId)?.let { users ->
                    val data = roomEventResponseMapper.toUsersChanged(users)
                    subscribers.forEach { it.event(RoomStreamEventType.USERS_CHANGED.code, data) }
                }
                RoomEventType.SETTINGS_CHANGED -> roomService.findSettings(event.roomId)?.let { settings ->
                    val data = roomEventResponseMapper.toSettingsChanged(settings)
                    subscribers.forEach { it.event(RoomStreamEventType.SETTINGS_CHANGED.code, data) }
                }
                RoomEventType.SESSION_CHANGED ->
                    subscribers.forEach { it.event(RoomStreamEventType.SESSION_CHANGED.code, roomEventResponseMapper.empty()) }
                RoomEventType.ROOM_DELETED ->
                    subscribers.forEach { it.lastEvent(RoomStreamEventType.ROOM_DELETED.code, roomEventResponseMapper.empty()) }
            }
        } catch (e: RuntimeException) {
            log.error("Failed to dispatch room event {}", event, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RoomStreamDispatcher::class.java)
    }
}
