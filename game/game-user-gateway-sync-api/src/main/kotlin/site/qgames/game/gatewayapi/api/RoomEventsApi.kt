package site.qgames.game.gatewayapi.api

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import java.io.InputStream
import java.util.UUID

/**
 * Поток событий комнаты (SSE) для gateway: клиент HTTP Interface, который отдаёт открытый поток ответа как есть.
 *
 * Сервис game этот интерфейс не реализует: Spring MVC не умеет отдавать [InputStream] по событиям, поэтому контроллер
 * стрима объявляет маршрут [PATH] сам и отвечает через SseEmitter (там же — его документация Swagger). Совпадение пути
 * проверяется тестами.
 *
 * Формат потока: сообщения `event: <тип>` + `data: <json>` (типы и данные — [site.qgames.game.gatewayapi.dto.room.RoomStreamEvent]),
 * первое сообщение — `snapshot`, раз в ~5 с комментарий `: ping`. Ошибки до начала потока — ProblemDetail
 * (RestClientResponseException на стороне клиента). Клиент должен закрыть поток, когда он больше не нужен: открытый
 * поток и есть нахождение пользователя в комнате.
 */
@HttpExchange
interface RoomEventsApi {

    @GetExchange(PATH, accept = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun roomEvents(@PathVariable roomId: UUID): InputStream

    companion object {
        const val PATH = "/api/rooms/{roomId}/events"
    }
}
