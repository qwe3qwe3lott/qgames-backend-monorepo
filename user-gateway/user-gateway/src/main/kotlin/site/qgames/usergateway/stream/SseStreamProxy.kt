package site.qgames.usergateway.stream

import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import java.io.InputStream

/**
 * Пересылка SSE-потока сервиса (комнаты, партии) клиенту байтами как есть: каждый прочитанный кусок — сразу клиенту
 * (ResponseBodyEmitter делает flush после каждой отправки; у StreamingResponseBody в Spring 7 flush выходного потока
 * игнорируется до конца ответа). SSE-кадры не разбираются. Ошибка до начала потока (ProblemDetail сервиса,
 * недоступность) бросается ещё при открытии upstream и уходит через DownstreamExceptionHandler. Отвалился клиент —
 * отправка падает и upstream закрывается; закрыл поток сервис — ответ завершается.
 */
@Component
class SseStreamProxy {

    /** [name] — имя потока пересылки (для отладки). */
    fun forward(name: String, upstream: InputStream): ResponseEntity<ResponseBodyEmitter> {
        val emitter = ResponseBodyEmitter(NO_TIMEOUT)
        val closeUpstream = Runnable { runCatching { upstream.close() } }
        emitter.onCompletion(closeUpstream)
        emitter.onError { closeUpstream.run() }
        emitter.onTimeout(closeUpstream)
        Thread.ofVirtual().name(name).start { copy(upstream, emitter) }
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .cacheControl(CacheControl.noCache())
            // Просит nginx-подобные прокси не буферизовать поток.
            .header(X_ACCEL_BUFFERING, "no")
            .body(emitter)
    }

    private fun copy(upstream: InputStream, emitter: ResponseBodyEmitter) {
        try {
            upstream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    emitter.send(buffer.copyOf(read), MediaType.APPLICATION_OCTET_STREAM)
                }
            }
            emitter.complete()
        } catch (e: Exception) {
            // Клиент отвалился (отправка не удалась) или сервис оборвал поток.
            log.debug("Proxied stream is closed: {}", e.toString())
            runCatching { emitter.complete() }
        }
    }

    companion object {
        /** 0 — без таймаута асинхронного запроса: поток живёт, пока его не закроет клиент или сервис. */
        private const val NO_TIMEOUT = 0L
        private const val BUFFER_SIZE = 8192
        private const val X_ACCEL_BUFFERING = "X-Accel-Buffering"
        private val log = LoggerFactory.getLogger(SseStreamProxy::class.java)
    }
}
