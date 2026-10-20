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
import org.springframework.web.service.annotation.PutExchange
import site.qgames.usergateway.publicapi.dto.room.CreateRoomRequest
import site.qgames.usergateway.publicapi.dto.game.GameCodes
import site.qgames.usergateway.publicapi.dto.room.MoveRequest
import site.qgames.usergateway.publicapi.dto.room.RoomResponse
import site.qgames.usergateway.publicapi.dto.room.RoomSettingsDto
import java.util.UUID

/**
 * Ручки комнат, которым нужен тип игры: он берётся из кода игры в пути (`/api/games/{gameCode}/rooms/…`).
 * Требуют аутентификации.
 */
@Tag(
    name = "Игровые комнаты",
    description = "Публичные комнаты: вход по ссылке с id комнаты для любого авторизованного пользователя. " +
        "Обновлений в реальном времени пока нет — клиент перечитывает комнату (GET). Все ручки требуют аутентификации.",
)
@HttpExchange("/api/games")
interface PublicGameRoomApi {

    @Operation(
        summary = "Создать комнату",
        description = "Создаёт комнату под игру из пути с заданными настройками: правила времени и настройки игры. " +
            "Создатель становится владельцем и первым игроком, ссылку на комнату (её id) можно отправить другим игрокам. " +
            "Игра должна быть доступна (PUBLISHED или HIDDEN): это проверяется до настроек. Границы настроек и " +
            "значения по умолчанию — в параметрах игры (GET /api/games/{gameCode}). Ошибки настроек отдаются по полям " +
            "тела (например, settings.gameSettings.rounds), как у валидации запроса.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Комната создана"),
        ApiResponse(
            responseCode = "400",
            description = "Настройки не подходят игре или запрос некорректен (VALIDATION_ERROR, поля — в errors)",
        ),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "404",
            description = "Неизвестный код игры, игра не опубликована или принудительно отключена (GAME_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Игра временно недоступна (GAME_UNAVAILABLE)",
        ),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
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
            "стартует с новыми настройками. Код игры в пути должен совпадать с игрой комнаты. Ошибки настроек " +
            "отдаются по полям тела (например, gameSettings.rounds).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Настройки изменены"),
        ApiResponse(
            responseCode = "400",
            description = "Настройки не подходят игре или запрос некорректен (VALIDATION_ERROR, поля — в errors)",
        ),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "403",
            description = "Пользователь не в комнате (NOT_ROOM_MEMBER) или не владелец (NOT_ROOM_OWNER)",
        ),
        ApiResponse(
            responseCode = "404",
            description = "Неизвестный код игры, игра не опубликована или принудительно отключена (GAME_NOT_FOUND), " +
                "комнаты нет (ROOM_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Комната другой игры (GAME_TYPE_MISMATCH) или идёт партия (ROOM_NOT_WAITING)",
        ),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
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
        description = "Ручка одна на все игры: код игры — в пути, ход — общий конверт `{type, data}`. `type` — вид " +
            "действия (`PASS` в «игре в ничто»), `data` — его параметры в формате игры. Код игры должен совпадать " +
            "с игрой комнаты. Ходить может только игрок партии, находящийся в комнате. Если у партии есть таймер, ход " +
            "переставляет дедлайн (session.moveDeadline). Когда партия заканчивается, комната возвращается в ожидание, " +
            "а законченная партия остаётся в ответе. Идущая партия продолжается, даже если игра стала временно " +
            "недоступной.",
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
            description = "Неизвестный код игры, игра не опубликована или принудительно отключена (GAME_NOT_FOUND), " +
                "комнаты нет (ROOM_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Комната другой игры (GAME_TYPE_MISMATCH), партия не идёт (ROOM_NOT_IN_GAME), стоит на паузе " +
                "(SESSION_PAUSED) или изменилась " +
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
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE]))
        @PathVariable gameCode: String,
        @PathVariable roomId: UUID,
        @Valid @RequestBody request: MoveRequest,
    ): RoomResponse
}
