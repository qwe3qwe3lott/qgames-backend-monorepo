package site.qgames.game.stream.session

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import site.qgames.game.stream.common.StreamIndex
import site.qgames.games.core.ViewerKey

/**
 * Открытые потоки партий: sessionId → подписчики. Как и реестр потоков комнат, привязан к соединениям; события
 * приходят через Postgres NOTIFY (RoomEventListener, канал session_events). При остановке приложения потоки
 * закрываются раньше веб-сервера.
 */
@Component
class SessionStreamRegistry : SmartLifecycle {

    private val sessions = StreamIndex<SessionStreamSubscriber> { it.sessionId }

    @Volatile
    private var running = false

    /** Новый подписчик, ждущий снапшот. Эмиттер без таймаута: поток живёт, пока его не закроет одна из сторон. */
    fun register(sessionId: UUID, viewer: ViewerKey): SessionStreamSubscriber =
        SessionStreamSubscriber(sessionId, viewer, SseEmitter(NO_TIMEOUT), sessions::remove).also(sessions::add)

    fun subscribers(sessionId: UUID): Collection<SessionStreamSubscriber> = sessions[sessionId]

    fun all(): List<SessionStreamSubscriber> = sessions.all()

    /** Закрывает все потоки (обрыв LISTEN-соединения, остановка): клиенты переподключатся и получат свежий снапшот. */
    fun closeAll() {
        val subscribers = all()
        if (subscribers.isEmpty()) return
        log.info("Closing {} session streams", subscribers.size)
        subscribers.forEach(SessionStreamSubscriber::close)
    }

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
        closeAll()
    }

    override fun isRunning() = running

    override fun getPhase() = SmartLifecycle.DEFAULT_PHASE

    companion object {
        /** 0 — без таймаута асинхронного запроса. */
        private const val NO_TIMEOUT = 0L
        private val log = LoggerFactory.getLogger(SessionStreamRegistry::class.java)
    }
}
