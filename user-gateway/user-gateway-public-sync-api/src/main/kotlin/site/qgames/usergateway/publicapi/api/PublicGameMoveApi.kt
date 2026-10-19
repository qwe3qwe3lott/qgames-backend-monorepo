package site.qgames.usergateway.publicapi.api

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
import site.qgames.usergateway.publicapi.dto.room.GameCodes
import site.qgames.usergateway.publicapi.dto.room.MoveRequest
import site.qgames.usergateway.publicapi.dto.room.RoomResponse
import java.util.UUID

/**
 * Ходы в партиях. Требует аутентификации.
 */
@Tag(
    name = "Игровые комнаты",
    description = "Публичные комнаты: вход по ссылке с id комнаты для любого авторизованного пользователя. " +
        "Обновлений в реальном времени пока нет — клиент перечитывает комнату (GET). Все ручки требуют аутентификации.",
)
@HttpExchange("/api/games")
interface PublicGameMoveApi {

    @Operation(
        summary = "Сделать ход",
        description = "Ручка одна на все игры: код игры — в пути, ход — общий конверт `{type, data}`. `type` — вид " +
            "действия (`PASS` в «игре в ничто»), `data` — его параметры в формате игры. Код игры должен совпадать " +
            "с игрой комнаты. Ходить может только игрок партии, находящийся в комнате. Когда партия заканчивается, " +
            "комната возвращается в ожидание, а законченная партия остаётся в ответе.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Ход принят, возвращено новое состояние"),
        ApiResponse(
            responseCode = "400",
            description = "Не указан вид хода или параметры не подходят игре (VALIDATION_ERROR)",
        ),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
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
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/{gameCode}/rooms/{roomId}/moves")
    fun makeMove(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING]))
        @PathVariable gameCode: String,
        @PathVariable roomId: UUID,
        @Valid @RequestBody request: MoveRequest,
    ): RoomResponse
}
