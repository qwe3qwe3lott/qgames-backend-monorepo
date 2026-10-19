package site.qgames.game.gatewayapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import site.qgames.game.gatewayapi.dto.room.GameCodes
import site.qgames.game.gatewayapi.dto.room.MoveRequest
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import java.util.UUID

/**
 * Ходы в партиях. Ручка одна на все игры: код игры — в пути, ход — общий конверт `{type, data}`,
 * параметры которого разбирает модуль игры.
 */
@Tag(
    name = "Игровые комнаты (для gateway)",
    description = "Внутренние ручки для user-gateway. Публичные комнаты: вход по ссылке с id комнаты для любого " +
        "авторизованного пользователя. Пользователь определяется по заголовку X-User-Id.",
)
@HttpExchange("/api/games")
interface GameMoveApi {

    @Operation(
        summary = "Сделать ход",
        description = "Применяет ход по правилам игры. Код игры в пути должен совпадать с игрой комнаты. Ходить может " +
            "только игрок партии, находящийся в комнате. Когда партия заканчивается, комната возвращается в ожидание.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Ход принят, возвращено новое состояние"),
        ApiResponse(
            responseCode = "400",
            description = "Не указан вид хода или параметры не подходят игре (VALIDATION_ERROR)",
        ),
        ApiResponse(responseCode = "401", description = "Нет заголовка X-User-Id (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "403",
            description = "Пользователь не в комнате (NOT_ROOM_MEMBER) или не игрок партии (NOT_SESSION_PLAYER)",
        ),
        ApiResponse(
            responseCode = "404",
            description = "Неизвестный код игры (GAME_NOT_FOUND) или комнаты нет (ROOM_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Комната другой игры (GAME_TYPE_MISMATCH), партия не идёт (ROOM_NOT_IN_GAME) или изменилась " +
                "с момента, который видел клиент (SESSION_CONFLICT)",
        ),
        ApiResponse(
            responseCode = "422",
            description = "Ход нарушает правила игры (MOVE_REJECTED, причина — в поле reason, например NOT_YOUR_TURN)",
        ),
    )
    @PostExchange("/{gameCode}/rooms/{roomId}/moves")
    fun makeMove(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING]))
        @PathVariable gameCode: String,
        @PathVariable roomId: UUID,
        @Valid @RequestBody request: MoveRequest,
    ): RoomResponse
}
