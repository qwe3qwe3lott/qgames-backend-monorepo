package site.qgames.game.gatewayapi.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import site.qgames.game.gatewayapi.dto.game.GameCodes
import site.qgames.game.gatewayapi.dto.game.GameDetailsResponse
import site.qgames.game.gatewayapi.dto.game.GameListResponse

/**
 * Каталог игр: описание и статус публикации — из БД сервиса, параметры правил — из модулей игр.
 * Доступен без пользователя (X-User-Id не нужен).
 */
@Tag(
    name = "Игры (для gateway)",
    description = "Внутренние ручки для user-gateway: каталог игр и параметры игры для формы настроек комнаты. " +
        "Пользователь не нужен.",
)
@HttpExchange("/api/games")
interface GameApi {

    @Operation(
        summary = "Каталог игр",
        description = "Игры в порядке показа с кодом, названием и описанием. Скрытые (HIDDEN) " +
            "и неопубликованные игры в список не попадают.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Каталог игр"),
    )
    @GetExchange
    fun listGames(): GameListResponse

    @Operation(
        summary = "Игра",
        description = "Описание и статус публикации игры, а также параметры правил: число игроков, режимы времени и " +
            "их границы, границы настроек игры и настройки по умолчанию. Скрытая игра отдаётся (доступ по прямой " +
            "ссылке).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Игра"),
        ApiResponse(
            responseCode = "404",
            description = "Неизвестный код игры или игра не опубликована (GAME_NOT_FOUND)",
        ),
    )
    @GetExchange("/{gameCode}")
    fun getGame(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE]))
        @PathVariable gameCode: String,
    ): GameDetailsResponse
}
