package site.qgames.game.stream.room

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Один открытый поток комнаты (SSE) пользователя [userId]. Живёт, пока открыт поток; состояние только в памяти
 * инстанса (оно привязано к соединению).
 *
 * Порядок событий защищён маркером подключения, потому что у событий комнаты нет seq:
 * 1. подписчик создаётся до транзакции подключения и до своего маркера отбрасывает события — они уже есть в снапшоте;
 * 2. после маркера события копятся, пока не отправлен снапшот;
 * 3. снапшот уходит первым, за ним накопленное, дальше события идут сразу.
 *
 * Запись в соединение — на собственном виртуальном потоке подписчика через очередь: медленный клиент не задерживает
 * раздачу событий остальным. Если очередь переполнена (клиент не читает), поток закрывается.
 *
 * Закрытие ([close]) идемпотентно: эмиттер завершается после уже поставленных в очередь сообщений, а [onClose]
 * (отключение от комнаты) вызывается ровно один раз.
 */
class RoomStreamSubscriber(
    val roomId: UUID,
    val userId: UUID,
    val emitter: SseEmitter,
    private val onClose: (RoomStreamSubscriber) -> Unit,
) {

    /** Id подписчика в маркере подключения. */
    val id: UUID = UUID.randomUUID()

    private val queue = LinkedBlockingQueue<Outgoing>(QUEUE_CAPACITY)
    private val lock = Any()
    private val closeLock = Any()
    private var closed = false
    private var markerReceived = false
    private var snapshotSent = false
    private val pending = mutableListOf<Outgoing>()

    /** Id подключения в room_connection; null — подключение ещё не создано или не удалось. */
    var connectionId: UUID? = null
        get() = synchronized(closeLock) { field }
        private set

    private val writer = Thread.ofVirtual().name("room-stream-$id").start(::writeLoop)

    init {
        emitter.onCompletion { close() }
        emitter.onTimeout { close() }
        emitter.onError { close() }
    }

    val isClosed: Boolean
        get() = synchronized(closeLock) { closed }

    /** Подключение создано. Если поток успели закрыть раньше, отключение выполняется сразу. */
    fun connected(connectionId: UUID) {
        val disconnect = synchronized(closeLock) {
            this.connectionId = connectionId
            closed
        }
        if (disconnect) {
            onClose(this)
        }
    }

    /** Пришёл собственный маркер подключения: дальше события не отбрасываются. */
    fun markerReceived() = synchronized(lock) { markerReceived = true }

    /** Событие комнаты: до маркера отбрасывается, до снапшота копится. */
    fun event(type: String, data: Any) = deliver(Outgoing.Event(type, data))

    /** Последнее событие потока: после него поток закрывается (комната удалена). */
    fun lastEvent(type: String, data: Any) {
        val delivered = synchronized(lock) {
            if (markerReceived) {
                deliver(Outgoing.Event(type, data))
                deliver(Outgoing.Complete)
            }
            markerReceived
        }
        if (!delivered) {
            close()
        }
    }

    /** Снапшот комнаты — первое сообщение потока; следом уходит накопленное после маркера. */
    fun snapshot(type: String, data: Any) = synchronized(lock) {
        enqueue(Outgoing.Event(type, data))
        snapshotSent = true
        pending.forEach(::enqueue)
        pending.clear()
    }

    /** Комментарий `: ping`: держит соединение и выявляет отвалившихся клиентов. */
    fun ping() = enqueue(Outgoing.Comment(PING))

    /** Закрывает поток и отключает пользователя от комнаты. Повторный вызов ничего не делает. */
    fun close() {
        val disconnect = synchronized(closeLock) {
            if (closed) return
            closed = true
            connectionId != null
        }
        queue.clear()
        queue.offer(Outgoing.Complete)
        if (disconnect) {
            onClose(this)
        }
    }

    private fun deliver(message: Outgoing) = synchronized(lock) {
        when {
            !markerReceived -> Unit
            !snapshotSent -> pending += message
            else -> enqueue(message)
        }
    }

    private fun enqueue(message: Outgoing) {
        if (isClosed) return
        if (!queue.offer(message)) {
            log.warn("Room stream {} of user {} is not read, closing it", roomId, userId)
            close()
        }
    }

    private fun writeLoop() {
        try {
            while (true) {
                when (val message = queue.take()) {
                    is Outgoing.Event -> emitter.send(SseEmitter.event().name(message.type).data(message.data, MediaType.APPLICATION_JSON))
                    is Outgoing.Comment -> emitter.send(SseEmitter.event().comment(message.text))
                    Outgoing.Complete -> {
                        emitter.complete()
                        return
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // Клиент отвалился: запись не удалась.
            log.debug("Room stream {} of user {} is broken: {}", roomId, userId, e.toString())
            runCatching { emitter.completeWithError(e) }
        } finally {
            close()
        }
    }

    private sealed interface Outgoing {
        data class Event(val type: String, val data: Any) : Outgoing
        data class Comment(val text: String) : Outgoing
        data object Complete : Outgoing
    }

    companion object {
        /** Spring пишет комментарий как ":" + текст: в потоке получится ": ping". */
        private const val PING = " ping"
        private const val QUEUE_CAPACITY = 1000
        private val log = LoggerFactory.getLogger(RoomStreamSubscriber::class.java)
    }
}
