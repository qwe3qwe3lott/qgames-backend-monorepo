package site.qgames.game.api.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import site.qgames.game.api.dto.game.GameCodes
import site.qgames.game.api.dto.room.MoveRequest
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.UpdateRoomSettingsRequest
import site.qgames.game.api.dto.session.SessionStreamEvent
import java.util.UUID

/**
 * Ручки комнат, которым нужен тип игры: он берётся из кода игры в пути (`/api/games/{gameCode}/rooms/…`).
 * Требуют аутентификации.
 */
@Tag(name = RoomApi.TAG, description = RoomApi.TAG_DESCRIPTION)
@HttpExchange("/api/games")
interface GameRoomApi {

    @Operation(
        summary = "Создать комнату",
        description = "Создаёт комнату под игру из пути с настройками игры по умолчанию (rules.defaultSettings в " +
            "GET /api/games/{gameCode}); владелец меняет их потом ручкой настроек. Тела у запроса нет. " +
            "Ссылку на комнату (её id) можно отправить другим игрокам. В ответе комната ещё пустая (members пустой, " +
            "ownerId = null): владельцем становится первый подключившийся к потоку событий комнаты — обычно сам " +
            "создатель, он входит игроком. Игра должна быть доступна (опубликована или скрыта из каталога).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Комната создана"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "404",
            description = "Неизвестный код игры или игра не опубликована (GAME_NOT_FOUND)",
        ),
    )
    @PostExchange("/{gameCode}/rooms")
    fun createRoom(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE, GameCodes.CHECKERS]))
        @PathVariable gameCode: String,
    ): RoomResponse

    @Operation(
        summary = "Изменить настройки комнаты",
        description = "Только владелец. Заменяет переданные группы настроек целиком, непереданные (null) не меняет; " +
            "нужна хотя бы одна группа. room (лимит наблюдателей) меняется в любой момент: понижение лимита вошедших " +
            "наблюдателей не выводит. simple и advanced взаимоисключающие и меняются только между партиями: simple " +
            "переводит комнату в simple-режим (advanced выводится из simple), advanced — в advanced-режим. Идущую партию " +
            "настройки не меняют — у неё свой снимок advanced. Код игры в пути должен совпадать с игрой комнаты. Ошибки " +
            "настроек отдаются по полям тела (например, room.maxSpectators, simple.rounds, advanced.timeControl.baseSeconds). " +
            "Ответ без тела: изменение приходит событием settings-changed потока комнаты.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Настройки изменены"),
        ApiResponse(
            responseCode = "400",
            description = "Настройки не подходят игре, переданы и simple, и advanced, не передано ни одной группы или " +
                "запрос некорректен (VALIDATION_ERROR, поля — в errors)",
        ),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
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
            description = "Комната другой игры (GAME_TYPE_MISMATCH) или simple/advanced во время партии (ROOM_NOT_WAITING)",
        ),
    )
    @PutExchange("/{gameCode}/rooms/{roomId}/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateRoomSettings(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE, GameCodes.CHECKERS]))
        @PathVariable gameCode: String,
        @PathVariable roomId: UUID,
        @Valid @RequestBody request: UpdateRoomSettingsRequest,
    )

    @Operation(
        summary = "Сделать ход",
        description = "Ручка одна на все игры: код игры — в пути, ход — общий конверт `{type, data}`. `type` — вид " +
            "действия (`PASS` в «игре в ничто», `MOVE` и `RESIGN` в шашках), `data` — его параметры в формате игры " +
            "(схема в components: у шашек — CheckersMoveDataDto, полный путь шашки); допустимые сейчас ходы игрока — " +
            "moveOptions его игрока в состоянии партии. Код игры должен совпадать с игрой комнаты. Ходить может только " +
            "игрок партии, находящийся в комнате. Ход проверяется по текущему состоянию партии. Ответ без тела: " +
            "изменения (ход, очерёдность, варианты ходов, таймер, конец партии) приходят событиями потока партии. Когда " +
            "партия заканчивается, комната возвращается в ожидание.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Ход принят"),
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
            description = "Ход нарушает правила игры (MOVE_REJECTED, причина — в поле reason: NOT_YOUR_TURN, " +
                "UNKNOWN_MOVE, GAME_FINISHED; в шашках ещё ILLEGAL_MOVE — путь не по правилам)",
        ),
    )
    @PostExchange("/{gameCode}/rooms/{roomId}/moves")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun makeMove(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE, GameCodes.CHECKERS]))
        @PathVariable gameCode: String,
        @PathVariable roomId: UUID,
        @Valid @RequestBody request: MoveRequest,
    )

    @Operation(
        summary = "Поток событий партии",
        description = "Server-Sent Events (EventSource с withCredentials): сообщения `event: <тип>` + `data: <json>`, " +
            "без `id:`, раз в ~5 с комментарий `: ping`. Схема пары тип + данные — SessionStreamEvent ({type, data}, " +
            "дискриминатор type).\n\n" +
            "Первое сообщение — snapshot: документ партии для этого зрителя `{seq, status, serverTime, meta, state}`; " +
            "так же после каждого переподключения. meta.game — код игры и advanced-настройки партии, закреплённые при " +
            "старте; по " +
            "meta.game.code клиент выбирает схему состояния (NothingSessionStateDto, CheckersSessionStateDto) и правил " +
            "итоговых правил meta.advancedRules (CheckersAdvancedRulesDto; null — у игры их нет). state — блоки core (players с " +
            "moveOptions и полями игры, timer, turns, result — итог партии) и поля игры.\n\n" +
            "Дальше — события журнала партии `{seq, at, method, code, data}` (SessionPatchEvent), тип сообщения — " +
            "method в нижнем регистре (put, patch, delete, info). seq непрерывен и продолжает seq снапшота: дыра " +
            "означает потерю (переподключиться), повтор с уже применённым seq отбрасывается. Каждое событие — одна " +
            "операция над документом `{status, meta, state}` по пути data.path (строка — ключ объекта, {id} — элемент " +
            "массива по id): PUT заменяет или создаёт значение, PATCH сливает поля объекта неглубоко, DELETE удаляет, " +
            "INFO состояние не меняет. Клиент применяет события по method, code — для журнала и логов. Начало и конец " +
            "партии — тоже патчи: session-started (PUT []), session-finished и session-cancelled (PATCH [] {status}), " +
            "после конца сервер закрывает поток. Несуществующий путь или состояние не по схеме игры — переподключиться " +
            "за свежим снапшотом.\n\n" +
            "События маскируются под зрителя: варианты ходов (moveOptions) видит только сам игрок, остальным приходит " +
            "пустой список, но событие доставляется. После конца партии всё публично: на завершённую или отменённую " +
            "партию приходит полный снапшот, и поток закрывается. Подписаться может участник комнаты (открытый поток " +
            "комнаты). Access-токен проверяется только при подключении.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Поток событий",
            content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = Schema(implementation = SessionStreamEvent::class))],
        ),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не в комнате (NOT_ROOM_MEMBER)"),
        ApiResponse(
            responseCode = "404",
            description = "Неизвестный код игры, игра не опубликована (GAME_NOT_FOUND), " +
                "комнаты нет (ROOM_NOT_FOUND), партии нет в этой комнате (SESSION_NOT_FOUND)",
        ),
        ApiResponse(responseCode = "409", description = "Комната другой игры (GAME_TYPE_MISMATCH)"),
    )
    @GetExchange("/{gameCode}/rooms/{roomId}/sessions/{sessionId}/events", accept = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun sessionEvents(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE, GameCodes.CHECKERS]))
        @PathVariable gameCode: String,
        @PathVariable roomId: UUID,
        @PathVariable sessionId: UUID,
    ): ResponseEntity<ResponseBodyEmitter>
}
