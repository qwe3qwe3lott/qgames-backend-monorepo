package site.qgames.game.gatewayapi.api

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import java.io.InputStream
import java.util.UUID

/**
 * Поток событий партии (SSE) для gateway: клиент HTTP Interface, который отдаёт открытый поток ответа как есть.
 *
 * Как и [RoomEventsApi], сервис game этот интерфейс не реализует: контроллер объявляет маршрут [PATH] сам и отвечает
 * через SseEmitter (там же — документация Swagger). Совпадение пути проверяется тестами.
 *
 * Формат потока: сообщения `event: <тип>` + `data: <json>` (типы и данные —
 * [site.qgames.game.gatewayapi.dto.session.SessionStreamEvent]), первое сообщение — `snapshot`, раз в ~5 с комментарий
 * `: ping`. Ошибки до начала потока — ProblemDetail (RestClientResponseException на стороне клиента).
 */
@HttpExchange
interface SessionEventsApi {

    @GetExchange(PATH, accept = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun sessionEvents(@PathVariable gameCode: String, @PathVariable roomId: UUID, @PathVariable sessionId: UUID): InputStream

    companion object {
        const val PATH = "/api/games/{gameCode}/rooms/{roomId}/sessions/{sessionId}/events"
    }
}
