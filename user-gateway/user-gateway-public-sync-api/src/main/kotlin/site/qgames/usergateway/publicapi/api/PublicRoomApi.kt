package site.qgames.usergateway.publicapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import site.qgames.game.gatewayapi.dto.room.ChangeRoleRequest
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomStreamEvent
import java.util.UUID

/**
 * Игровые комнаты и партии: ручки, которым не нужен тип игры. Создание комнаты, настройки и ходы — в
 * [PublicGameRoomApi]. Все ручки требуют аутентификации. Простое проксирование в game: DTO — из его контракта
 * (game-user-gateway-sync-api), без собственных дублей.
 */
@Tag(
    name = "Игровые комнаты",
    description = "Публичные комнаты: вход по ссылке с id комнаты для любого авторизованного пользователя. " +
        "Изменения комнаты приходят в реальном времени через поток событий (GET /api/rooms/{roomId}/events), открытый " +
        "поток и есть нахождение в комнате. Все ручки требуют аутентификации.",
)
@HttpExchange("/api/rooms")
interface PublicRoomApi {

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
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована " +
                "или принудительно отключена (GAME_NOT_FOUND)",
        ),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @GetExchange("/{roomId}")
    fun getRoom(@PathVariable roomId: UUID): RoomResponse

    @Operation(
        summary = "Поток событий комнаты",
        description = "Server-Sent Events (EventSource с withCredentials): сообщения `event: <тип>` + `data: <json>`, " +
            "без `id:`, раз в ~5 с комментарий `: ping`. Первое сообщение — snapshot: комната целиком для этого " +
            "пользователя, как в GET /api/rooms/{roomId}; так же после каждого переподключения. Дальше — users-changed " +
            "(владелец и полный список участников: вход, выход, уход, смена роли, ника, владельца), settings-changed " +
            "(настройки комнаты), session-changed (старт, отмена, пауза, продолжение, конец партии — перечитать комнату " +
            "через GET) и room-deleted (комната удалена, после него сервер закрывает поток). События несут полное " +
            "значение, повтор безопасен. Схема пары тип + данные — RoomStreamEvent ({type, data}, дискриминатор type).\n\n" +
            "Открытый поток и есть нахождение в комнате: подключение входит в комнату (игрок идущей партии возвращается " +
            "на своё место; иначе игрок, если партия не идёт и есть место, или наблюдатель; в пустой комнате — " +
            "владелец; повторное подключение, в том числе в пределах срока выхода, роль и владение не меняет). " +
            "Подключений у пользователя может быть несколько (вкладки, устройства). Когда закрывается последнее, выход " +
            "отложенный: участник помечается уходящим (leaving) и до истечения срока сохраняет роль, место и владение. " +
            "Срок: игроку идущей партии его задаёт игра (в «игре в ничто» 30 с, на паузе отсчёт заморожен), владельцу " +
            "вне игры — 15 с, остальным — 1 с. По истечении срока участник удаляется: игрок идущей партии сохраняет " +
            "место, владение переходит к самому раннему из оставшихся (сначала присутствующие, в каждой группе сначала " +
            "игроки). Создатель комнаты, так и не подключившийся к потоку, удаляется через 15 с. Пустая комната " +
            "удаляется через 30 минут. Обрыв соединения сервер замечает с задержкой (по неудачной записи ping, обычно 10–30 с). " +
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
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована " +
                "или принудительно отключена (GAME_NOT_FOUND)",
        ),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
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
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована " +
                "или принудительно отключена (GAME_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Идёт партия (ROOM_NOT_WAITING) или нет свободного места игрока (NO_FREE_SEAT)",
        ),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PutExchange("/{roomId}/role")
    fun changeRole(@PathVariable roomId: UUID, @Valid @RequestBody request: ChangeRoleRequest): RoomResponse

    @Operation(
        summary = "Начать партию",
        description = "Стартует партию с текущими игроками комнаты (места — по порядку входа). Только владелец, " +
            "число игроков должно укладываться в ограничения игры (minPlayers–maxPlayers). Игра должна быть доступна " +
            "(PUBLISHED или HIDDEN).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Партия началась"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не владелец (NOT_ROOM_OWNER)"),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована " +
                "или принудительно отключена (GAME_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Партия уже идёт (ROOM_NOT_WAITING), игра временно недоступна (GAME_UNAVAILABLE), " +
                "число игроков не подходит игре (NOT_ENOUGH_PLAYERS)",
        ),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/{roomId}/session")
    fun startSession(@PathVariable roomId: UUID): RoomResponse

    @Operation(
        summary = "Отменить партию",
        description = "Завершает идущую партию без результата и возвращает комнату в ожидание. Работает и на паузе. " +
            "Только владелец.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Партия отменена"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не владелец (NOT_ROOM_OWNER)"),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована " +
                "или принудительно отключена (GAME_NOT_FOUND)",
        ),
        ApiResponse(responseCode = "409", description = "Партия не идёт (ROOM_NOT_IN_GAME)"),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @DeleteExchange("/{roomId}/session")
    fun cancelSession(@PathVariable roomId: UUID): RoomResponse

    @Operation(
        summary = "Поставить партию на паузу",
        description = "Замораживает идущую партию: ходы запрещены, время хода и сроки возвращения вышедших игроков " +
            "партии не идут (session.pausedAt; остаток времени хода — moveDeadline − pausedAt). Вход, выход и отмена " +
            "партии работают. Только владелец. Повторный вызов на паузе ничего не меняет.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Партия на паузе"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "403",
            description = "Пользователь не в комнате (NOT_ROOM_MEMBER) или не владелец (NOT_ROOM_OWNER)",
        ),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована " +
                "или принудительно отключена (GAME_NOT_FOUND)",
        ),
        ApiResponse(responseCode = "409", description = "Партия не идёт (ROOM_NOT_IN_GAME)"),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/{roomId}/session/pause")
    fun pauseSession(@PathVariable roomId: UUID): RoomResponse

    @Operation(
        summary = "Продолжить партию",
        description = "Снимает паузу. Время хода и сроки возвращения вышедших игроков партии сдвигаются на длительность " +
            "паузы: остаётся столько же времени, сколько было в момент паузы. Только владелец. Без паузы вызов ничего " +
            "не меняет.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Партия продолжается"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "403",
            description = "Пользователь не в комнате (NOT_ROOM_MEMBER) или не владелец (NOT_ROOM_OWNER)",
        ),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована " +
                "или принудительно отключена (GAME_NOT_FOUND)",
        ),
        ApiResponse(responseCode = "409", description = "Партия не идёт (ROOM_NOT_IN_GAME)"),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/{roomId}/session/resume")
    fun resumeSession(@PathVariable roomId: UUID): RoomResponse
}
