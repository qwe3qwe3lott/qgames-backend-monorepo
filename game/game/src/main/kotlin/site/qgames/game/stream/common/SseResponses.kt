package site.qgames.game.stream.common

import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/** Ответ SSE-ручки: поток не кешируется и не буферизуется прокси (nginx-подобные смотрят на X-Accel-Buffering). */
object SseResponses {

    private const val X_ACCEL_BUFFERING = "X-Accel-Buffering"

    fun stream(emitter: SseEmitter): ResponseEntity<ResponseBodyEmitter> =
        ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .cacheControl(CacheControl.noCache())
            .header(X_ACCEL_BUFFERING, "no")
            .body(emitter)
}
