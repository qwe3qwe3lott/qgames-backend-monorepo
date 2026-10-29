package site.qgames.game.stream.room

import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import site.qgames.game.service.room.RoomService
import site.qgames.game.stream.common.StreamIndex
import java.util.UUID

/**
 * Открытые потоки комнат: roomId → подписчики. Состояние в памяти допустимо — оно привязано к соединениям
 * (CLAUDE.md §6); события приходят через Postgres NOTIFY (RoomEventListener).
 *
 * Закрытие потока отключает пользователя от комнаты ([RoomService.disconnect]). При остановке приложения потоки
 * закрываются раньше веб-сервера: клиенты переподключатся после перезапуска.
 */
@Component
class RoomStreamRegistry(
    private val roomService: RoomService,
) : SmartLifecycle {

    private val rooms = StreamIndex<RoomStreamSubscriber> { it.roomId }

    @Volatile
    private var running = false

    /** Новый подписчик: до снапшота копит события. Эмиттер без таймаута: поток живёт, пока его не закроет одна из сторон. */
    fun register(roomId: UUID, userId: UUID): RoomStreamSubscriber =
        RoomStreamSubscriber(roomId, userId, SseEmitter(NO_TIMEOUT), ::onClosed).also(rooms::add)

    fun subscribers(roomId: UUID): Collection<RoomStreamSubscriber> = rooms[roomId]

    fun all(): List<RoomStreamSubscriber> = rooms.all()

    /** Закрывает все потоки (обрыв LISTEN-соединения, остановка). Отключения от комнат идут параллельно. */
    fun closeAll() {
        val subscribers = all()
        if (subscribers.isEmpty()) return
        log.info("Closing {} room streams", subscribers.size)
        subscribers.map { subscriber -> Thread.ofVirtual().start { subscriber.close() } }.forEach(Thread::join)
    }

    private fun onClosed(subscriber: RoomStreamSubscriber) {
        rooms.remove(subscriber)
        val connectionId = subscriber.connectionId ?: return
        try {
            roomService.disconnect(connectionId, subscriber.roomId, subscriber.userId)
        } catch (e: RuntimeException) {
            // Подключение удалит RoomConnectionCleanupJob, когда истечёт аренда.
            log.error("Failed to disconnect user {} from room {}", subscriber.userId, subscriber.roomId, e)
        }
    }

    /** Убирает подписчика, который так и не подключился (ошибка до начала потока). */
    fun discard(subscriber: RoomStreamSubscriber) {
        rooms.remove(subscriber)
        subscriber.close()
    }

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
        closeAll()
    }

    override fun isRunning() = running

    /** Останавливается первым — раньше веб-сервера, пока БД ещё доступна для отключений. */
    override fun getPhase() = SmartLifecycle.DEFAULT_PHASE

    companion object {
        /** 0 — без таймаута асинхронного запроса. */
        private const val NO_TIMEOUT = 0L
        private val log = LoggerFactory.getLogger(RoomStreamRegistry::class.java)
    }
}
