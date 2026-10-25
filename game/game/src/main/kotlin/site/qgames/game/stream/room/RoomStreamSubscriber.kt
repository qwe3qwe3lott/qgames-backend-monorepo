package site.qgames.game.stream.room

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import site.qgames.game.stream.common.SseWriter
import java.util.UUID

/**
 * Один открытый поток комнаты (SSE) пользователя [userId]. Живёт, пока открыт поток; состояние только в памяти
 * процесса (оно привязано к соединению). Запись — через [SseWriter].
 *
 * Порядок событий защищён маркером подключения, потому что у событий комнаты нет seq:
 * 1. подписчик создаётся до транзакции подключения и до своего маркера отбрасывает события — они уже есть в снапшоте;
 * 2. после маркера события копятся, пока не отправлен снапшот;
 * 3. снапшот уходит первым, за ним накопленное, дальше события идут сразу.
 *
 * Закрытие ([close]) идемпотентно, а [onClose] (отключение от комнаты) вызывается ровно один раз — когда закрыт поток
 * и создано подключение, в каком бы порядке это ни произошло.
 */
class RoomStreamSubscriber(
    val roomId: UUID,
    val userId: UUID,
    emitter: SseEmitter,
    private val onClose: (RoomStreamSubscriber) -> Unit,
) {

    /** Id подписчика в маркере подключения. */
    val id: UUID = UUID.randomUUID()

    private val lock = Any()
    private val closeLock = Any()
    private var closed = false
    private var markerReceived = false
    private var snapshotSent = false
    private val pending = mutableListOf<() -> Unit>()

    private val writer = SseWriter("room-stream-$id", emitter, ::closed)

    val emitter: SseEmitter
        get() = writer.emitter

    /** Id подключения в room_connection; null — подключение ещё не создано или не удалось. */
    var connectionId: UUID? = null
        get() = synchronized(closeLock) { field }
        private set

    val isClosed: Boolean
        get() = writer.isClosed

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
    fun event(type: String, data: Any) = deliver { writer.event(type, data) }

    /** Последнее событие потока: после него поток закрывается (комната удалена). */
    fun lastEvent(type: String, data: Any) {
        val delivered = synchronized(lock) {
            if (markerReceived) {
                deliver { writer.event(type, data) }
                deliver { writer.complete() }
            }
            markerReceived
        }
        if (!delivered) {
            close()
        }
    }

    /** Снапшот комнаты — первое сообщение потока; следом уходит накопленное после маркера. */
    fun snapshot(type: String, data: Any) = synchronized(lock) {
        writer.event(type, data)
        snapshotSent = true
        pending.forEach { it() }
        pending.clear()
    }

    /** Комментарий `: ping`: держит соединение и выявляет отвалившихся клиентов. */
    fun ping() = writer.ping()

    /** Закрывает поток и отключает пользователя от комнаты. Повторный вызов ничего не делает. */
    fun close() = writer.close()

    /** Поток закрыт (сервером или клиентом): если подключение уже создано, пользователь отключается. */
    private fun closed() {
        val disconnect = synchronized(closeLock) {
            closed = true
            connectionId != null
        }
        if (disconnect) {
            onClose(this)
        }
    }

    private fun deliver(send: () -> Unit) = synchronized(lock) {
        when {
            !markerReceived -> Unit
            !snapshotSent -> pending += send
            else -> send()
        }
    }
}
