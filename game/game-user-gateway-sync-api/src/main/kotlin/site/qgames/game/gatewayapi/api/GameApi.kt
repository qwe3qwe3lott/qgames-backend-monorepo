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
import site.qgames.game.gatewayapi.dto.game.GameInfoResponse
import site.qgames.game.gatewayapi.dto.room.GameCodes

/**
 * Параметры подключённых игр. Строятся из правил игр, а не из БД.
 */
@Tag(
    name = "Игры (для gateway)",
    description = "Внутренние ручки для user-gateway: параметры игр для формы настроек комнаты. " +
        "Пользователь определяется по заголовку X-User-Id.",
)
@HttpExchange("/api/games")
interface GameApi {

    @Operation(
        summary = "Параметры игры",
        description = "Число игроков, поддерживаемые режимы времени и их границы, границы настроек игры и настройки " +
            "по умолчанию. Нужны для формы создания комнаты и смены настроек.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Параметры игры"),
        ApiResponse(responseCode = "401", description = "Нет заголовка X-User-Id (UNAUTHENTICATED)"),
        ApiResponse(responseCode = "404", description = "Неизвестный код игры (GAME_NOT_FOUND)"),
    )
    @GetExchange("/{gameCode}")
    fun getGame(
        @Parameter(description = "Код игры", schema = Schema(allowableValues = [GameCodes.NOTHING]))
        @PathVariable gameCode: String,
    ): GameInfoResponse
}
