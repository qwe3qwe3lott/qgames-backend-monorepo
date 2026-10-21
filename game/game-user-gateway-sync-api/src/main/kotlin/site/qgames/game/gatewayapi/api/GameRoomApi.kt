package site.qgames.game.gatewayapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange
import site.qgames.game.gatewayapi.dto.room.CreateRoomRequest
import site.qgames.game.gatewayapi.dto.game.GameCodes
import site.qgames.game.gatewayapi.dto.room.MoveRequest
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import java.util.UUID

/**
 * Ручки комнат, которым нужен тип игры: он берётся из кода игры в пути (`/api/games/{gameCode}/rooms/…`).
 * Ход — общий конверт `{type, data}`, параметры которого разбирает модуль игры.
 */
@Tag(
    name = "Игровые комнаты (для gateway)",
    description = "Внутренние ручки для user-gateway. Публичные комнаты: вход по ссылке с id комнаты для любого " +
        "авторизованного пользователя. Пользователь определяется по заголовку X-User-Id.",
)
@HttpExchange("/api/games")
interface GameRoomApi {

    @Operation(
        summary = "Создать комнату",
        description = "Создаёт комнату под игру из пути с заданными настройками. Комната создаётся пустой и без " +
            "владельца, с окном владения 15 с за создателем: подключившись к потоку комнаты, он входит игроком и " +
            "становится владельцем. Игра должна быть доступна (опубликована или скрыта из каталога): это проверяется " +
            "до настроек. Настройки проверяются по правилам игры, ошибки отдаются по полям, как у валидации запроса.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Комната создана"),
        ApiResponse(
            responseCode = "400",
            description = "Настройки не подходят игре или запрос некорректен (VALIDATION_ERROR, поля — в errors)",
        ),
        ApiResponse(responseCode = "401", description = "Нет заголовка X-User-Id (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "404",
            description = "Неизвестный код игры или игра не опубликована (GAME_NOT_FOUND)",
        ),
    )
    @PostExchange("/{gameCode}/rooms")
    fun createRoom(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE]))
        @PathVariable gameCode: String,
        @Valid @RequestBody request: CreateRoomRequest,
    ): RoomResponse

    @Operation(
        summary = "Изменить настройки комнаты",
        description = "Заменяет настройки комнаты целиком. Только владелец и только между партиями; следующая партия " +
            "стартует с новыми настройками. Код игры в пути должен совпадать с игрой комнаты.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Настройки изменены"),
        ApiResponse(
            responseCode = "400",
            description = "Настройки не подходят игре или запрос некорректен (VALIDATION_ERROR, поля — в errors)",
        ),
        ApiResponse(responseCode = "401", description = "Нет заголовка X-User-Id (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "403",
            description = "Пользователь не в комнате (NOT_ROOM_MEMBER) или не владелец (NOT_ROOM_OWNER)",
        ),
        ApiResponse(
            responseCode = "404",
            description = "Неизвестный код игры, игра не опубликована (GAME_NOT_FOUND), " +
                "комнаты нет (ROOM_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Комната другой игры (GAME_TYPE_MISMATCH) или идёт партия (ROOM_NOT_WAITING)",
        ),
    )
    @PutExchange("/{gameCode}/rooms/{roomId}/settings")
    fun updateRoomSettings(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE]))
        @PathVariable gameCode: String,
        @PathVariable roomId: UUID,
        @Valid @RequestBody request: RoomSettingsDto,
    ): RoomResponse

    @Operation(
        summary = "Сделать ход",
        description = "Применяет ход по правилам игры к текущему состоянию партии (под блокировкой комнаты). Код игры в " +
            "пути должен совпадать с игрой комнаты. Ходить может только игрок партии, находящийся в комнате. Ответ без " +
            "тела: изменения (ход, очерёдность, варианты ходов, таймер, конец партии) приходят событиями потока партии. " +
            "Когда партия заканчивается, комната возвращается в ожидание.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Ход принят"),
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
            description = "Неизвестный код игры, игра не опубликована (GAME_NOT_FOUND), " +
                "комнаты нет (ROOM_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Комната другой игры (GAME_TYPE_MISMATCH), партия не идёт (ROOM_NOT_IN_GAME) или таймер " +
                "партии на паузе (SESSION_PAUSED)",
        ),
        ApiResponse(
            responseCode = "422",
            description = "Ход нарушает правила игры (MOVE_REJECTED, причина — в поле reason, например NOT_YOUR_TURN)",
        ),
    )
    @PostExchange("/{gameCode}/rooms/{roomId}/moves")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun makeMove(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE]))
        @PathVariable gameCode: String,
        @PathVariable roomId: UUID,
        @Valid @RequestBody request: MoveRequest,
    )
}
