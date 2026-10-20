package site.qgames.game.stream.room

import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import site.qgames.game.service.room.RoomService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Открытые на этом инстансе потоки комнат: roomId → подписчики. Состояние в памяти допустимо — оно привязано к
 * соединениям (CLAUDE.md §6), а между инстансами события расходятся через Postgres NOTIFY (RoomEventListener).
 *
 * Закрытие потока отключает пользователя от комнаты ([RoomService.disconnect]). При остановке приложения потоки
 * закрываются раньше веб-сервера: клиенты переподключатся к другому инстансу.
 */
@Component
class RoomStreamRegistry(
    private val roomService: RoomService,
) : SmartLifecycle {

    private val rooms = ConcurrentHashMap<UUID, MutableSet<RoomStreamSubscriber>>()

    @Volatile
    private var running = false

    /** Новый подписчик в режиме «ждёт маркер». Эмиттер без таймаута: поток живёт, пока его не закроет одна из сторон. */
    fun register(roomId: UUID, userId: UUID): RoomStreamSubscriber {
        val subscriber = RoomStreamSubscriber(roomId, userId, SseEmitter(NO_TIMEOUT), ::onClosed)
        // Добавление — внутри атомарной операции над ключом: иначе параллельное удаление последнего подписчика могло бы
        // выкинуть множество, и новый подписчик остался бы в «осиротевшем» множестве без событий и продления аренды.
        rooms.compute(roomId) { _, set -> (set ?: ConcurrentHashMap.newKeySet()).apply { add(subscriber) } }
        return subscriber
    }

    fun subscribers(roomId: UUID): Collection<RoomStreamSubscriber> = rooms[roomId].orEmpty()

    fun all(): List<RoomStreamSubscriber> = rooms.values.flatten()

    /** Закрывает все потоки (обрыв LISTEN-соединения, остановка). Отключения от комнат идут параллельно. */
    fun closeAll() {
        val subscribers = all()
        if (subscribers.isEmpty()) return
        log.info("Closing {} room streams", subscribers.size)
        subscribers.map { subscriber -> Thread.ofVirtual().start { subscriber.close() } }.forEach(Thread::join)
    }

    private fun onClosed(subscriber: RoomStreamSubscriber) {
        remove(subscriber)
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
        remove(subscriber)
        subscriber.close()
    }

    /** Удаление тоже атомарно по ключу: опустевшее множество убирается в той же операции, что и подписчик. */
    private fun remove(subscriber: RoomStreamSubscriber) {
        rooms.computeIfPresent(subscriber.roomId) { _, set -> set.apply { remove(subscriber) }.takeIf { it.isNotEmpty() } }
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
