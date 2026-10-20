package site.qgames.usergateway.controller.room

import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import site.qgames.game.gatewayapi.api.RoomApi
import site.qgames.game.gatewayapi.api.RoomEventsApi
import site.qgames.game.gatewayapi.dto.room.ChangeRoleRequest
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.usergateway.publicapi.api.PublicRoomApi
import java.io.InputStream
import java.util.UUID

/**
 * Требует аутентификации. X-User-Id в game проставляет UserIdPropagationInterceptor. Простое проксирование: запросы
 * и ответы — DTO контракта game как есть.
 */
@RestController
class RoomGatewayController(
    private val roomApi: RoomApi,
    private val roomEventsApi: RoomEventsApi,
) : PublicRoomApi {

    override fun getRoom(roomId: UUID): RoomResponse = roomApi.getRoom(roomId)

    /**
     * Поток game пересылается байтами как есть, каждый прочитанный кусок — сразу клиенту (ResponseBodyEmitter делает
     * flush после каждой отправки; у StreamingResponseBody в Spring 7 flush выходного потока игнорируется до конца
     * ответа). SSE-кадры не разбираются. Ошибка до начала потока (ProblemDetail game, недоступность) бросается здесь же
     * и уходит через DownstreamExceptionHandler. Отвалился клиент — отправка падает и upstream закрывается; закрыл поток
     * game — ответ завершается. JWT проверяется только при подключении.
     */
    override fun roomEvents(roomId: UUID): ResponseEntity<ResponseBodyEmitter> {
        val upstream = roomEventsApi.roomEvents(roomId)
        val emitter = ResponseBodyEmitter(NO_TIMEOUT)
        val closeUpstream = Runnable { runCatching { upstream.close() } }
        emitter.onCompletion(closeUpstream)
        emitter.onError { closeUpstream.run() }
        emitter.onTimeout(closeUpstream)
        Thread.ofVirtual().name("room-events-proxy-$roomId").start { forward(upstream, emitter) }
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .cacheControl(CacheControl.noCache())
            // Просит nginx-подобные прокси не буферизовать поток.
            .header(X_ACCEL_BUFFERING, "no")
            .body(emitter)
    }

    override fun changeRole(roomId: UUID, request: ChangeRoleRequest): RoomResponse = roomApi.changeRole(roomId, request)

    override fun startSession(roomId: UUID): RoomResponse = roomApi.startSession(roomId)

    override fun cancelSession(roomId: UUID): RoomResponse = roomApi.cancelSession(roomId)

    override fun pauseSession(roomId: UUID): RoomResponse = roomApi.pauseSession(roomId)

    override fun resumeSession(roomId: UUID): RoomResponse = roomApi.resumeSession(roomId)

    private fun forward(upstream: InputStream, emitter: ResponseBodyEmitter) {
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
            // Клиент отвалился (отправка не удалась) или game оборвал поток.
            log.debug("Room events stream is closed: {}", e.toString())
            runCatching { emitter.complete() }
        }
    }

    companion object {
        /** 0 — без таймаута асинхронного запроса: поток живёт, пока его не закроет клиент или game. */
        private const val NO_TIMEOUT = 0L
        private const val BUFFER_SIZE = 8192
        private const val X_ACCEL_BUFFERING = "X-Accel-Buffering"
        private val log = LoggerFactory.getLogger(RoomGatewayController::class.java)
    }
}
