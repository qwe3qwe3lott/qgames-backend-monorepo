package site.qgames.game.controller.session

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.gatewayapi.api.SessionEventsApi
import site.qgames.game.gatewayapi.dto.game.GameCodes
import site.qgames.game.gatewayapi.dto.session.SessionStreamEvent
import site.qgames.game.gatewayapi.dto.session.SessionStreamEventTypes
import site.qgames.game.mapper.session.SessionEventResponseMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.service.session.SessionStreamService
import site.qgames.game.stream.session.SessionStreamRegistry
import site.qgames.games.api.session.SessionStatus
import java.util.UUID

/**
 * Поток событий партии (SSE). Как и поток комнаты, исключение из правила «контроллер реализует интерфейс контракта»:
 * клиент gateway ([SessionEventsApi]) читает поток как InputStream, а сервер отвечает через [SseEmitter], поэтому
 * маршрут берётся из константы [SessionEventsApi.PATH], а документация — здесь.
 *
 * Порядок подключения: проверка доступа (ошибки — обычным ProblemDetail) → регистрация подписчика → снапшот зрителя на
 * последнем seq N (кеш + доигрывание) → события, пришедшие раньше снапшота, с seq ≤ N отбрасываются.
 */
@Tag(name = "Игровые комнаты (для gateway)")
@RestController
class SessionEventsController(
    private val registry: SessionStreamRegistry,
    private val sessionStreamService: SessionStreamService,
    private val sessionEventResponseMapper: SessionEventResponseMapper,
    private val requestUserContext: RequestUserContext,
) {

    @Operation(
        summary = "Поток событий партии",
        description = "Server-Sent Events: `event: <тип>`, `data: <json>`, раз в ~5 с комментарий `: ping`. Первое " +
            "сообщение — snapshot: состояние партии для этого зрителя на seq ({seq, status, serverTime, meta, state}). " +
            "Дальше события журнала в кадре {seq, at, data} с непрерывным seq, замаскированные под зрителя: игрок видит " +
            "свои варианты ходов, остальные — пустой список. Конец или отмена партии закрывают поток. На завершённую " +
            "партию приходит полный снапшот, и поток закрывается. Подписаться может участник комнаты.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Поток событий",
            content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = Schema(implementation = SessionStreamEvent::class))],
        ),
        ApiResponse(responseCode = "401", description = "Нет заголовка X-User-Id (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не в комнате (NOT_ROOM_MEMBER)"),
        ApiResponse(
            responseCode = "404",
            description = "Неизвестный код игры, игра не опубликована или принудительно отключена (GAME_NOT_FOUND), " +
                "комнаты нет (ROOM_NOT_FOUND), партии нет в этой комнате (SESSION_NOT_FOUND)",
        ),
        ApiResponse(responseCode = "409", description = "Комната другой игры (GAME_TYPE_MISMATCH)"),
    )
    @GetMapping(SessionEventsApi.PATH, produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun sessionEvents(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE]))
        @PathVariable gameCode: String,
        @PathVariable roomId: UUID,
        @PathVariable sessionId: UUID,
    ): SseEmitter {
        val code = GameCode.fromCode(gameCode) ?: throw RoomErrors.gameNotFound()
        val subscription = sessionStreamService.subscribe(requestUserContext.userId(), code, roomId, sessionId)
        val subscriber = registry.register(sessionId, subscription.viewer)
        try {
            val snapshot = sessionStreamService.snapshot(sessionId, subscription.viewer) ?: throw RoomErrors.sessionNotFound()
            subscriber.snapshot(
                seq = snapshot.state.seq,
                type = SessionStreamEventTypes.SNAPSHOT,
                data = sessionEventResponseMapper.toSnapshot(snapshot),
                last = snapshot.state.session.status != SessionStatus.IN_PROGRESS,
            )
        } catch (e: Exception) {
            subscriber.close()
            throw e
        }
        return subscriber.emitter
    }
}
