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
import site.qgames.game.api.dto.room.TransferOwnershipRequest
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
            "без `id:`, раз в ~5 с комментарий `: ping`. Первое сообщение — snapshot: seq и комната целиком (документ " +
            "потока, как в GET /api/rooms/{roomId}); так же после каждого переподключения. Дальше — события-патчи " +
            "документа `{seq, at, method, code, data}` (RoomPatchEvent) с непрерывным seq, тип сообщения — метод в " +
            "нижнем регистре: клиент применяет их по порядку общим применителем (тем же, что у партии), не зная кодов. " +
            "Разрыв seq или неприменимый патч — рассинхронизация: клиент переподключается за свежим снапшотом. " +
            "room-deleted (INFO) — последнее событие, после него сервер закрывает поток. Пауза и продолжение партии — " +
            "game-paused и game-resumed потока партии. Схема пары тип + данные — RoomStreamEvent ({type, data}, " +
            "дискриминатор type).\n\n" +
            "Открытый поток и есть нахождение в комнате: подключение входит в комнату (игрок идущей партии возвращается " +
            "на своё место; иначе игрок, если партия не идёт и есть место, или наблюдатель; в комнате без владельца — " +
            "владелец; повторное подключение роль и владение не меняет). Наблюдателей не больше лимита комнаты " +
            "(settings.room.maxSpectators): если новому участнику не досталось места игрока, а наблюдатели заняли " +
            "все места, поток не открывается (409 NO_SPECTATOR_SEAT). Понижение лимита вошедших не выводит. Подключений у " +
            "пользователя может быть несколько (вкладки, устройства). Когда закрывается последнее, участник сразу " +
            "удаляется из комнаты, таймеров выхода нет; вернувшись, он входит заново (при занятых местах — " +
            "наблюдателем). Исключение — игроки идущей партии: они закреплены до её конца и остаются в списке с " +
            "leaving = true, а конец или отмена партии удаляют отсутствующих.\n\n" +
            "Ушёл владелец — владение сразу получает случайный присутствующий игрок, а если игроков нет — случайный " +
            "наблюдатель (отсутствующие игроки партии владельцами не становятся). Если присутствующих нет, владельца " +
            "нет (ownerId = null), и им станет первый вошедший — так и в новой комнате: первым обычно подключается " +
            "создатель. Комната без присутствующих участников " +
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
        ApiResponse(
            responseCode = "409",
            description = "Мест нет: места игрока не досталось, а наблюдатели заняли все места (NO_SPECTATOR_SEAT)",
        ),
    )
    @GetExchange("/{roomId}/events", accept = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun roomEvents(@PathVariable roomId: UUID): ResponseEntity<ResponseBodyEmitter>

    @Operation(
        summary = "Сменить роль",
        description = "Стать игроком или наблюдателем. Только между партиями; игроком — если есть свободное место, " +
            "наблюдателем — если наблюдателей меньше лимита комнаты (settings.room.maxSpectators). " +
            "Владелец тоже может стать наблюдателем, владение при этом сохраняется. Ответ без тела: изменение приходит " +
            "событием role-changed потока комнаты.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Роль изменена"),
        ApiResponse(responseCode = "400", description = "Не указана роль (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не в комнате (NOT_ROOM_MEMBER)"),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована (GAME_NOT_FOUND)",
        ),
        ApiResponse(
            responseCode = "409",
            description = "Идёт партия (ROOM_NOT_WAITING), нет свободного места игрока (NO_FREE_SEAT) или " +
                "наблюдателя (NO_SPECTATOR_SEAT)",
        ),
    )
    @PutExchange("/{roomId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changeRole(@PathVariable roomId: UUID, @Valid @RequestBody request: ChangeRoleRequest)

    @Operation(
        summary = "Передать владение",
        description = "Владелец назначает владельцем другого присутствующего участника комнаты. Работает в любой момент, " +
            "в том числе во время партии и на паузе. Отсутствующий игрок партии (leaving = true) владельцем не " +
            "становится. Передача самому себе ничего не меняет. Ответ без тела: изменение приходит событием " +
            "owner-changed потока комнаты.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Владение передано"),
        ApiResponse(responseCode = "400", description = "Не указан новый владелец (VALIDATION_ERROR)"),
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
            description = "Новый владелец не участник комнаты или отсутствующий игрок партии (NEW_OWNER_NOT_PRESENT)",
        ),
    )
    @PutExchange("/{roomId}/owner")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun transferOwnership(@PathVariable roomId: UUID, @Valid @RequestBody request: TransferOwnershipRequest)

    @Operation(
        summary = "Исключить участника",
        description = "Владелец исключает участника из комнаты в любой момент, в том числе игрока идущей партии. " +
            "Это не бан: исключённый может войти снова. Игрок идущей партии остаётся её игроком (отсутствующим, " +
            "leaving = true) до конца партии, на его отсутствие отвечает игра (таймаут хода), при повторном входе он " +
            "возвращается на своё место. Все подключения исключённого к потоку комнаты закрываются. Исключение " +
            "неучастника или отсутствующего игрока ничего не меняет. Ответ без тела: изменение приходит событием " +
            "member-kicked и следом member-left или member-leaving потока комнаты.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Участник исключён"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "403",
            description = "Пользователь не в комнате (NOT_ROOM_MEMBER) или не владелец (NOT_ROOM_OWNER)",
        ),
        ApiResponse(
            responseCode = "404",
            description = "Комнаты нет или она удалена (ROOM_NOT_FOUND), игра комнаты не опубликована (GAME_NOT_FOUND)",
        ),
        ApiResponse(responseCode = "409", description = "Владелец исключает сам себя (CANNOT_KICK_SELF)"),
    )
    @DeleteExchange("/{roomId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun kickMember(@PathVariable roomId: UUID, @PathVariable userId: UUID)

    @Operation(
        summary = "Начать партию",
        description = "Стартует партию с текущими игроками комнаты (места — по порядку входа). Только владелец, " +
            "число игроков должно укладываться в ограничения игры (minPlayers–maxPlayers). Игра должна быть доступна " +
            "(опубликована или скрыта из каталога). Ответ без тела: поток комнаты получает session-started, по " +
            "session.id клиент подписывается на поток партии.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Партия началась"),
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun startSession(@PathVariable roomId: UUID)

    @Operation(
        summary = "Отменить партию",
        description = "Завершает идущую партию без результата и возвращает комнату в ожидание. Работает и на паузе. " +
            "Только владелец. Ответ без тела: поток партии получает session-cancelled и закрывается, поток комнаты — " +
            "session-ended.",
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
