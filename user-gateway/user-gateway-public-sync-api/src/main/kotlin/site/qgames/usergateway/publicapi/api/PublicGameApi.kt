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
import site.qgames.usergateway.publicapi.dto.game.GameInfoResponse
import site.qgames.usergateway.publicapi.dto.room.GameCodes

/**
 * Параметры игр. Требует аутентификации.
 */
@Tag(name = "Игры", description = "Параметры игр портала. Все ручки требуют аутентификации.")
@HttpExchange("/api/games")
interface PublicGameApi {

    @Operation(
        summary = "Параметры игры",
        description = "Число игроков, поддерживаемые режимы времени и границы времени на ход, границы настроек игры " +
            "и настройки по умолчанию. По ним строится форма создания комнаты и смены настроек.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Параметры игры"),
        ApiResponse(responseCode = "401", description = "Нет действительного access-токена (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "404", description = "Неизвестный код игры (GAME_NOT_FOUND)"),
        ApiResponse(responseCode = "503", description = "Игровой сервис недоступен (SERVICE_UNAVAILABLE)"),
    )
    @GetExchange("/{gameCode}")
    fun getGame(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING]))
        @PathVariable gameCode: String,
    ): GameInfoResponse
}
