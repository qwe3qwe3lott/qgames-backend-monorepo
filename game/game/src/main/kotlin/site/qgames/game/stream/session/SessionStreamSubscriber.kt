package site.qgames.game.stream.session

import java.util.UUID
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import site.qgames.game.stream.common.SseWriter
import site.qgames.games.core.ViewerKey

/**
 * Один открытый поток партии (SSE): пользователь [userId] комнаты [roomId] с проекцией [viewer]. Аренды и присутствия
 * у потока партии нет: нахождение в комнате держит поток комнаты, а уход из неё закрывает и потоки партий.
 *
 * Порядок защищён seq, маркер не нужен: подписчик регистрируется до сборки снапшота, события до снапшота копятся,
 * снапшот на seq N уходит первым, из накопленного и последующих событий отбрасываются те, что с seq ≤ N.
 * Последнее событие партии (конец, отмена) закрывает поток.
 */
class SessionStreamSubscriber(
    val roomId: UUID,
    val sessionId: UUID,
    val userId: UUID,
    val viewer: ViewerKey,
    emitter: SseEmitter,
    onClose: (SessionStreamSubscriber) -> Unit,
) {

    private val lock = Any()
    private var snapshotSeq: Long? = null
    private val pending = mutableListOf<Pending>()

    private val writer = SseWriter("session-stream-$sessionId-${UUID.randomUUID()}", emitter) { onClose(this) }

    val emitter: SseEmitter
        get() = writer.emitter

    val isClosed: Boolean
        get() = writer.isClosed

    /** Событие журнала [seq]: до снапшота копится, после — уходит, если оно новее снапшота. [last] — закрыть поток после него. */
    fun event(seq: Long, type: String, data: Any, last: Boolean) = synchronized(lock) {
        val sent = snapshotSeq
        when {
            sent == null -> pending += Pending(seq, type, data, last)
            seq > sent -> send(type, data, last)
        }
    }

    /** Снапшот на seq [seq] — первое сообщение потока. [last] — партия уже закончилась: поток закрывается следом. */
    fun snapshot(seq: Long, type: String, data: Any, last: Boolean) = synchronized(lock) {
        writer.event(type, data)
        snapshotSeq = seq
        if (last) {
            writer.complete()
        } else {
            pending.filter { it.seq > seq }.forEach { send(it.type, it.data, it.last) }
        }
        pending.clear()
    }

    fun ping() = writer.ping()

    fun close() = writer.close()

    private fun send(type: String, data: Any, last: Boolean) {
        writer.event(type, data)
        if (last) writer.complete()
    }

    private data class Pending(val seq: Long, val type: String, val data: Any, val last: Boolean)
}
