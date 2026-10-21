package site.qgames.game.controller.room

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import site.qgames.common.web.context.RequestUserContext
import site.qgames.game.gatewayapi.api.RoomEventsApi
import site.qgames.game.gatewayapi.dto.room.RoomStreamEvent
import site.qgames.game.gatewayapi.dto.room.RoomStreamEventType
import site.qgames.game.mapper.room.RoomEventResponseMapper
import site.qgames.game.service.room.RoomService
import site.qgames.game.stream.room.RoomStreamRegistry
import java.util.UUID

/**
 * Поток событий комнаты (SSE). Исключение из правила «контроллер реализует интерфейс контракта»: клиент gateway
 * ([RoomEventsApi]) читает поток как InputStream, а сервер отвечает через [SseEmitter], поэтому маршрут берётся из
 * константы [RoomEventsApi.PATH], а документация — здесь.
 *
 * Порядок подключения защищает от гонки снапшота и событий (у событий комнаты нет seq): подписчик регистрируется до
 * транзакции подключения и отбрасывает события до своего маркера; маркер публикуется в той же транзакции после чтения
 * снапшота; снапшот уходит первым, следом — события после маркера.
 */
@Tag(name = "Игровые комнаты (для gateway)")
@RestController
class RoomEventsController(
    private val registry: RoomStreamRegistry,
    private val roomService: RoomService,
    private val roomEventResponseMapper: RoomEventResponseMapper,
    private val requestUserContext: RequestUserContext,
) {

    @Operation(
        summary = "Поток событий комнаты",
        description = "Server-Sent Events: `event: <тип>`, `data: <json>`, раз в ~5 с комментарий `: ping`. Первое " +
            "сообщение — snapshot (комната для этого пользователя, как в GET), дальше — изменения участников, настроек, " +
            "партии и удаление комнаты. Открытый поток и есть нахождение в комнате: подключение входит в комнату (как " +
            "прежний вход), а когда у пользователя закрывается последний поток, он выходит из комнаты (игрок идущей " +
            "партии остаётся отсутствующим до её конца). " +
            "Подключений у пользователя может быть несколько (вкладки, устройства).",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Поток событий",
            content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = Schema(implementation = RoomStreamEvent::class))],
        ),
        ApiResponse(responseCode = "401", description = "Нет заголовка X-User-Id (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована (GAME_NOT_FOUND)",
        ),
    )
    @GetMapping(RoomEventsApi.PATH, produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun roomEvents(@PathVariable roomId: UUID): SseEmitter {
        val userId = requestUserContext.userId()
        val subscriber = registry.register(roomId, userId)
        try {
            val connection = roomService.connect(userId, roomId, subscriber.id)
            // С этого момента закрытие подписчика отключает пользователя (удаляет подключение, выводит из комнаты).
            subscriber.connected(connection.connectionId)
            subscriber.snapshot(RoomStreamEventType.SNAPSHOT.code, roomEventResponseMapper.toSnapshot(connection.details))
        } catch (e: Exception) {
            // Ошибка до начала потока: подписчик убирается, а если подключение уже создано — пользователь отключается
            // (registry.discard закрывает подписчика, закрытие вызывает disconnect). Иначе остался бы эмиттер, который
            // никогда не завершится, и участник с вечно продлеваемой арендой.
            registry.discard(subscriber)
            throw e
        }
        return subscriber.emitter
    }
}
