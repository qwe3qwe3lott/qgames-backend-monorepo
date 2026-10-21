package site.qgames.game.stream.common

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Запись одного SSE-потока (комнаты или партии): очередь сообщений и собственный виртуальный поток записи. Медленный
 * клиент не задерживает раздачу событий остальным; если очередь переполнена (клиент не читает), поток закрывается.
 *
 * Закрытие ([close]) идемпотентно: эмиттер завершается, очередь сбрасывается, а [onClosed] вызывается ровно один раз —
 * и при закрытии сервером, и при обрыве клиента (неудачная запись), и при завершении после [complete].
 */
class SseWriter(
    private val name: String,
    val emitter: SseEmitter,
    private val onClosed: () -> Unit,
) {

    private val queue = LinkedBlockingQueue<Outgoing>(QUEUE_CAPACITY)
    private val closed = AtomicBoolean(false)

    init {
        emitter.onCompletion { close() }
        emitter.onTimeout { close() }
        emitter.onError { close() }
        Thread.ofVirtual().name(name).start(::writeLoop)
    }

    val isClosed: Boolean
        get() = closed.get()

    /** Сообщение `event: <type>` + `data: <json>`. */
    fun event(type: String, data: Any) = enqueue(Outgoing.Event(type, data))

    /** Комментарий `: ping`: держит соединение и выявляет отвалившихся клиентов. */
    fun ping() = enqueue(Outgoing.Comment(PING))

    /** Завершает поток после уже поставленных в очередь сообщений. */
    fun complete() = enqueue(Outgoing.Complete)

    /** Закрывает поток сразу, без недоставленных сообщений. Повторный вызов ничего не делает. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.clear()
        queue.offer(Outgoing.Complete)
        onClosed()
    }

    private fun enqueue(message: Outgoing) {
        if (isClosed) return
        if (!queue.offer(message)) {
            log.warn("Stream {} is not read, closing it", name)
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
            log.debug("Stream {} is broken: {}", name, e.toString())
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
        private val log = LoggerFactory.getLogger(SseWriter::class.java)
    }
}
