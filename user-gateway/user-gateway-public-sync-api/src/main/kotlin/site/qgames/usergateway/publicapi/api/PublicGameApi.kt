package site.qgames.usergateway.publicapi.api

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
 * Каталог игр. Доступен без аутентификации.
 */
@Tag(name = "Игры", description = "Каталог игр портала и параметры игры. Ручки доступны без аутентификации.")
@HttpExchange("/api/games")
interface PublicGameApi {

    @Operation(
        summary = "Каталог игр",
        description = "Игры в порядке показа: код, название, описание и статус публикации. Скрытые игры в список не " +
            "попадают. Игры со статусом DISABLED (временно недоступна) показываются, но создать для них комнату " +
            "нельзя. Доступна без аутентификации.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Каталог игр"),
        ApiResponse(responseCode = "401", description = "Access-токен в куке недействителен или истёк (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
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
            description = "Неизвестный код игры, игра не опубликована или принудительно отключена (GAME_NOT_FOUND)",
        ),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @GetExchange("/{gameCode}")
    fun getGame(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING, GameCodes.CARCASSONNE]))
        @PathVariable gameCode: String,
    ): GameDetailsResponse
}
