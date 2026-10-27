package site.qgames.game.api.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import site.qgames.game.api.dto.game.GameCodes
import site.qgames.game.api.dto.game.GameDetailsResponse
import site.qgames.game.api.dto.game.GameListResponse
import site.qgames.game.api.dto.room.GameAdvancedSettingsDto
import site.qgames.game.api.dto.room.GameSimpleSettingsDto

/**
 * Каталог игр (доступен без аутентификации) и перевод настроек игры.
 */
@Tag(
    name = "games",
    description = "Игры. Каталог игр портала и параметры игры (доступны без аутентификации), перевод simple-настроек " +
        "игры в advanced.",
)
@HttpExchange("/api/games")
interface GameApi {

    @Operation(
        summary = "Каталог игр",
        description = "Игры в порядке показа: код, название и описание. Скрытые и неопубликованные игры в список не " +
            "попадают. Доступна без аутентификации.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Каталог игр"),
        ApiResponse(responseCode = "401", description = "Access-токен в куке недействителен или истёк (UNAUTHENTICATED)"),
    )
    @GetExchange
    fun listGames(): GameListResponse

    @Operation(
        summary = "Игра",
        description = "Название, описание и статус публикации игры, а также параметры правил (rules): число игроков, " +
            "границы лимита наблюдателей, " +
            "границы часов, границы настроек игры и пресеты времени, настройки по умолчанию. По ним строятся " +
            "карточка игры и форма настроек комнаты. Скрытая игра доступна по прямой ссылке. " +
            "Доступна без аутентификации.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Игра"),
        ApiResponse(responseCode = "401", description = "Access-токен в куке недействителен или истёк (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "404",
            description = "Неизвестный код игры или игра не опубликована (GAME_NOT_FOUND)",
        ),
    )
    @GetExchange("/{gameCode}")
    fun getGame(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE, GameCodes.CHECKERS]))
        @PathVariable gameCode: String,
    ): GameDetailsResponse

    @Operation(
        summary = "Перевести simple-настройки в advanced",
        description = "Выводит advanced-настройки игры из simple-настроек: правила варианта и часы пресета (у " +
            "пресета без таймера часы выключены, но заполнены). Ничего не сохраняет. Нужна форме настроек: при " +
            "включении продвинутых правил она заполняет advanced-поля ответом. Ошибки отдаются по полям тела " +
            "(например, variant), как у валидации запроса; gameCode тела должен совпадать с кодом игры в пути.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Advanced-настройки"),
        ApiResponse(
            responseCode = "400",
            description = "Настройки не подходят игре или запрос некорректен (VALIDATION_ERROR, поля — в errors)",
        ),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(
            responseCode = "404",
            description = "Неизвестный код игры или игра не опубликована (GAME_NOT_FOUND)",
        ),
    )
    @PostExchange("/{gameCode}/advanced-settings")
    fun toAdvancedSettings(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE, GameCodes.CHECKERS]))
        @PathVariable gameCode: String,
        @Valid @RequestBody request: GameSimpleSettingsDto,
    ): GameAdvancedSettingsDto
}
