package site.qgames.game.api.web

import io.swagger.v3.oas.annotations.Operation
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
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import site.qgames.game.api.dto.room.ChangeRoleRequest
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.RoomStreamEvent
import java.util.UUID

/**
 * Игровые комнаты и партии: ручки, которым не нужен тип игры. Создание комнаты, настройки и ходы — в
 * [GameRoomApi]. Все ручки требуют аутентификации.
 */
@Tag(name = RoomApi.TAG, description = RoomApi.TAG_DESCRIPTION)
@HttpExchange("/api/rooms")
interface RoomApi {

    @Operation(
        summary = "Получить комнату",
        description = "Участники с ролями, владелец, текущая или последняя партия. Доступно и до входа в комнату " +
            "(подключения к потоку событий) — так страница по ссылке показывает комнату ещё до входа.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Комната"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована (GAME_NOT_FOUND)",
        ),
    )
    @GetExchange("/{roomId}")
    fun getRoom(@PathVariable roomId: UUID): RoomResponse

    @Operation(
        summary = "Поток событий комнаты",
        description = "Server-Sent Events (EventSource с withCredentials): сообщения `event: <тип>` + `data: <json>`, " +
            "без `id:`, раз в ~5 с комментарий `: ping`. Первое сообщение — snapshot: комната целиком для этого " +
            "пользователя, как в GET /api/rooms/{roomId}; так же после каждого переподключения. Дальше — users-changed " +
            "(владелец и полный список участников: вход, выход, уход, смена роли, ника, владельца), settings-changed " +
            "(настройки комнаты), session-changed (старт, отмена, конец партии — перечитать комнату через GET и " +
            "подписаться на поток партии session.id; пауза и продолжение — game-paused и game-resumed потока партии) " +
            "и room-deleted (комната удалена, после него сервер закрывает поток). События несут полное " +
            "значение, повтор безопасен. Схема пары тип + данные — RoomStreamEvent ({type, data}, дискриминатор type).\n\n" +
            "Открытый поток и есть нахождение в комнате: подключение входит в комнату (игрок идущей партии возвращается " +
            "на своё место; иначе игрок, если партия не идёт и есть место, или наблюдатель; в комнате без владельца и " +
            "без окна владения — владелец; повторное подключение роль и владение не меняет). Подключений у " +
            "пользователя может быть несколько (вкладки, устройства). Когда закрывается последнее, участник сразу " +
            "удаляется из комнаты, таймеров выхода нет; вернувшись, он входит заново (при занятых местах — " +
            "наблюдателем). Исключение — игроки идущей партии: они закреплены до её конца и остаются в списке с " +
            "leaving = true, а конец или отмена партии удаляют отсутствующих.\n\n" +
            "Ушёл владелец — у комнаты 15 с нет владельца (ownerId = null, команды владельца не может выполнить " +
            "никто): вернувшись в этот срок, он снова владелец. Потом владение получает самый ранний присутствующий " +
            "игрок, затем наблюдатель, а если присутствующих нет — первый вошедший. Новая комната ждёт создателя так " +
            "же: подключившись, он входит игроком и становится владельцем. Комната без присутствующих участников " +
            "удаляется через 30 минут (идущая партия при этом отменяется). Обрыв соединения сервер замечает с задержкой (по неудачной записи ping, обычно 10–30 с). " +
            "Access-токен проверяется только при подключении: при истечении поток не рвётся.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Поток событий",
            content = [Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = Schema(implementation = RoomStreamEvent::class))],
        ),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована (GAME_NOT_FOUND)",
        ),
    )
    @GetExchange("/{roomId}/events", accept = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun roomEvents(@PathVariable roomId: UUID): ResponseEntity<ResponseBodyEmitter>

    @Operation(
        summary = "Сменить роль",
        description = "Стать игроком или наблюдателем. Только между партиями; игроком — если есть свободное место. " +
            "Владелец тоже может стать наблюдателем, владение при этом сохраняется.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Роль изменена"),
        ApiResponse(responseCode = "400", description = "Не указана роль (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не в комнате (NOT_ROOM_MEMBER)"),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована (GAME_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Идёт партия (ROOM_NOT_WAITING) или нет свободного места игрока (NO_FREE_SEAT)",
        ),
    )
    @PutExchange("/{roomId}/role")
    fun changeRole(@PathVariable roomId: UUID, @Valid @RequestBody request: ChangeRoleRequest): RoomResponse

    @Operation(
        summary = "Начать партию",
        description = "Стартует партию с текущими игроками комнаты (места — по порядку входа). Только владелец, " +
            "число игроков должно укладываться в ограничения игры (minPlayers–maxPlayers). Игра должна быть доступна " +
            "(опубликована или скрыта из каталога). В ответе комната; по session.id клиент подписывается на поток " +
            "партии.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Партия началась"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не владелец (NOT_ROOM_OWNER)"),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована (GAME_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Партия уже идёт (ROOM_NOT_WAITING), " +
                "число игроков не подходит игре (NOT_ENOUGH_PLAYERS)",
        ),
    )
    @PostExchange("/{roomId}/session")
    fun startSession(@PathVariable roomId: UUID): RoomResponse

    @Operation(
        summary = "Отменить партию",
        description = "Завершает идущую партию без результата и возвращает комнату в ожидание. Работает и на паузе. " +
            "Только владелец. Ответ без тела: поток партии получает session-cancelled и закрывается, поток комнаты — " +
            "session-changed.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Партия отменена"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не владелец (NOT_ROOM_OWNER)"),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована (GAME_NOT_FOUND)",
        ),
        ApiResponse(responseCode = "409", description = "Партия не идёт (ROOM_NOT_IN_GAME)"),
    )
    @DeleteExchange("/{roomId}/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelSession(@PathVariable roomId: UUID)

    @Operation(
        summary = "Поставить партию на паузу",
        description = "Пауза — действие таймера партии: ходы запрещены, время хода не идёт (state.timer: status PAUSED, " +
            "pausedAt; остаток времени хода — deadline − pausedAt). Окно владения пауза не замораживает. " +
            "Вход, выход и отмена партии работают. Только владелец. Ответ без тела: изменение приходит событием " +
            "game-paused потока партии. Повторный вызов на паузе ничего не меняет. У партии без таймера паузы нет.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Партия на паузе"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "403",
            description = "Пользователь не в комнате (NOT_ROOM_MEMBER) или не владелец (NOT_ROOM_OWNER)",
        ),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована (GAME_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Партия не идёт (ROOM_NOT_IN_GAME) или у неё нет таймера (SESSION_NOT_PAUSABLE)",
        ),
    )
    @PostExchange("/{roomId}/session/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun pauseSession(@PathVariable roomId: UUID)

    @Operation(
        summary = "Продолжить партию",
        description = "Снимает паузу таймера. Время хода сдвигается на длительность паузы: остаётся столько же " +
            "времени, сколько было в момент паузы. Только владелец. Ответ без " +
            "тела: изменение приходит событием game-resumed потока партии. Без паузы вызов ничего не меняет.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Партия продолжается"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "403",
            description = "Пользователь не в комнате (NOT_ROOM_MEMBER) или не владелец (NOT_ROOM_OWNER)",
        ),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована (GAME_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Партия не идёт (ROOM_NOT_IN_GAME) или у неё нет таймера (SESSION_NOT_PAUSABLE)",
        ),
    )
    @PostExchange("/{roomId}/session/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resumeSession(@PathVariable roomId: UUID)

    companion object {
        /**
         * Тег всех ручек комнат и партий ([RoomApi], [GameRoomApi]). Описание у одноимённых тегов должно совпадать,
         * иначе в OpenAPI окажется несколько тегов с одним именем.
         */
        const val TAG = "rooms"
        const val TAG_DESCRIPTION = "Игровые комнаты. Публичные комнаты: вход по ссылке с id комнаты для любого авторизованного пользователя. " +
            "Изменения комнаты приходят в реальном времени через поток событий (GET /api/rooms/{roomId}/events), открытый " +
            "поток и есть нахождение в комнате. Состояние партии приходит через её поток (GET " +
            "/api/games/{gameCode}/rooms/{roomId}/sessions/{sessionId}/events). Все ручки требуют аутентификации."
    }
}
