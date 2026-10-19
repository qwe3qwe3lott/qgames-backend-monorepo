package site.qgames.usergateway.publicapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange
import site.qgames.usergateway.publicapi.dto.room.ChangeRoleRequest
import site.qgames.usergateway.publicapi.dto.room.CreateRoomRequest
import site.qgames.usergateway.publicapi.dto.room.RoomResponse
import java.util.UUID

/**
 * Игровые комнаты и партии. Все ручки требуют аутентификации.
 */
@Tag(
    name = "Игровые комнаты",
    description = "Публичные комнаты: вход по ссылке с id комнаты для любого авторизованного пользователя. " +
        "Обновлений в реальном времени пока нет — клиент перечитывает комнату (GET). Все ручки требуют аутентификации.",
)
@HttpExchange("/api/rooms")
interface PublicRoomApi {

    @Operation(
        summary = "Создать комнату",
        description = "Создаёт комнату под выбранную игру. Создатель становится владельцем и первым игроком. " +
            "Ссылку на комнату (её id) можно отправить другим игрокам.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Комната создана"),
        ApiResponse(responseCode = "400", description = "Не указан или неизвестен тип игры (VALIDATION_ERROR)"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange
    fun createRoom(@Valid @RequestBody request: CreateRoomRequest): RoomResponse

    @Operation(
        summary = "Получить комнату",
        description = "Участники с ролями, владелец, текущая или последняя партия. Доступно и до входа в комнату — " +
            "так страница по ссылке показывает комнату перед кнопкой «Войти».",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Комната"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "404", description = "Комнаты нет или она удалена (ROOM_NOT_FOUND)"),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @GetExchange("/{roomId}")
    fun getRoom(@PathVariable roomId: UUID): RoomResponse

    @Operation(
        summary = "Войти в комнату",
        description = "Игрок идущей партии возвращается на своё место. Иначе, если партия не идёт и есть свободное место, " +
            "пользователь становится игроком, в остальных случаях — наблюдателем. В пустой комнате вошедший становится " +
            "владельцем. Повторный вход ничего не меняет.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Пользователь в комнате"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "404", description = "Комнаты нет или она удалена (ROOM_NOT_FOUND)"),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/{roomId}/join")
    fun joinRoom(@PathVariable roomId: UUID): RoomResponse

    @Operation(
        summary = "Выйти из комнаты",
        description = "Выйти можно всегда. Игрок идущей партии сохраняет место и может вернуться. Если вышел владелец, " +
            "владение переходит к самому раннему из оставшихся: сначала игроки, затем наблюдатели. Пустая комната " +
            "удаляется автоматически через 30 минут.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Пользователь вышел"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не в комнате (NOT_ROOM_MEMBER)"),
        ApiResponse(responseCode = "404", description = "Комнаты нет или она удалена (ROOM_NOT_FOUND)"),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/{roomId}/leave")
    fun leaveRoom(@PathVariable roomId: UUID)

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
        ApiResponse(responseCode = "404", description = "Комнаты нет или она удалена (ROOM_NOT_FOUND)"),
        ApiResponse(
            responseCode = "409",
            description = "Идёт партия (ROOM_NOT_WAITING) или нет свободного места игрока (NO_FREE_SEAT)",
        ),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PutExchange("/{roomId}/role")
    fun changeRole(@PathVariable roomId: UUID, @Valid @RequestBody request: ChangeRoleRequest): RoomResponse

    @Operation(summary = "Удалить комнату", description = "Удаляет комнату вместе с историей партий. Только владелец.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Комната удалена"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не владелец (NOT_ROOM_OWNER)"),
        ApiResponse(responseCode = "404", description = "Комнаты нет или она удалена (ROOM_NOT_FOUND)"),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @DeleteExchange("/{roomId}")
    fun deleteRoom(@PathVariable roomId: UUID)

    @Operation(
        summary = "Начать партию",
        description = "Стартует партию с текущими игроками комнаты (места — по порядку входа). Только владелец, " +
            "число игроков должно укладываться в ограничения игры (minPlayers–maxPlayers).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Партия началась"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не владелец (NOT_ROOM_OWNER)"),
        ApiResponse(responseCode = "404", description = "Комнаты нет или она удалена (ROOM_NOT_FOUND)"),
        ApiResponse(
            responseCode = "409",
            description = "Партия уже идёт (ROOM_NOT_WAITING) или число игроков не подходит игре (NOT_ENOUGH_PLAYERS)",
        ),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @PostExchange("/{roomId}/session")
    fun startSession(@PathVariable roomId: UUID): RoomResponse

    @Operation(
        summary = "Отменить партию",
        description = "Завершает идущую партию без результата и возвращает комнату в ожидание. Только владелец.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Партия отменена"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "403", description = "Пользователь не владелец (NOT_ROOM_OWNER)"),
        ApiResponse(responseCode = "404", description = "Комнаты нет или она удалена (ROOM_NOT_FOUND)"),
        ApiResponse(responseCode = "409", description = "Партия не идёт (ROOM_NOT_IN_GAME)"),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @DeleteExchange("/{roomId}/session")
    fun cancelSession(@PathVariable roomId: UUID): RoomResponse
}
