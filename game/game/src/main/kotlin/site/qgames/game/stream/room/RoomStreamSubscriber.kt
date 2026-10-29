package site.qgames.game.stream.room

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import site.qgames.game.stream.common.SseWriter
import java.util.UUID

/**
 * Один открытый поток комнаты (SSE) пользователя [userId]. Живёт, пока открыт поток; состояние только в памяти
 * процесса (оно привязано к соединению). Запись — через [SseWriter].
 *
 * Порядок защищён seq, как у потока партии: подписчик регистрируется до транзакции подключения, события до снапшота
 * копятся, снапшот на seq N уходит первым, из накопленного и последующих событий отбрасываются те, что с seq ≤ N (они
 * уже в снапшоте). Последнее событие (комната удалена) закрывает поток.
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

    private val lock = Any()
    private val closeLock = Any()
    private var closed = false
    private var snapshotSeq: Long? = null
    private val pending = mutableListOf<Pending>()

    private val writer = SseWriter("room-stream-${UUID.randomUUID()}", emitter, ::closed)

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

    /** Событие [seq]: до снапшота копится, после — уходит, если оно новее снапшота. [last] — закрыть поток после него. */
    fun event(seq: Long, type: String, data: Any, last: Boolean) = synchronized(lock) {
        val sent = snapshotSeq
        when {
            sent == null -> pending += Pending(seq, type, data, last)
            seq > sent -> send(type, data, last)
        }
    }

    /** Снапшот на seq [seq] — первое сообщение потока; следом уходит накопленное новее снапшота. */
    fun snapshot(seq: Long, type: String, data: Any) = synchronized(lock) {
        writer.event(type, data)
        snapshotSeq = seq
        pending.filter { it.seq > seq }.forEach { send(it.type, it.data, it.last) }
        pending.clear()
    }

    /** Комментарий `: ping`: держит соединение и выявляет отвалившихся клиентов. */
    fun ping() = writer.ping()

    /** Закрывает поток и отключает пользователя от комнаты. Повторный вызов ничего не делает. */
    fun close() = writer.close()

    private fun send(type: String, data: Any, last: Boolean) {
        writer.event(type, data)
        if (last) writer.complete()
    }

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

    private data class Pending(val seq: Long, val type: String, val data: Any, val last: Boolean)
}
