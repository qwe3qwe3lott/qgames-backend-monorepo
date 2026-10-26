package site.qgames.game.api.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import site.qgames.game.api.dto.game.GameCodes
import site.qgames.game.api.dto.game.GameDetailsResponse
import site.qgames.game.api.dto.game.GameListResponse

/**
 * Каталог игр. Доступен без аутентификации.
 */
@Tag(name = "games", description = "Игры. Каталог игр портала и параметры игры. Ручки доступны без аутентификации.")
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
            "режимы времени и границы времени на ход, границы настроек игры и настройки по умолчанию. По ним строятся " +
            "карточка игры и форма создания комнаты и смены настроек. Скрытая игра доступна по прямой ссылке. " +
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
}
