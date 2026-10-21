package site.qgames.usergateway.controller.game

import org.springframework.web.bind.annotation.RestController
import site.qgames.game.gatewayapi.api.GameApi
import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.game.GameDetailsResponse
import site.qgames.game.gatewayapi.dto.game.GameListResponse
import site.qgames.usergateway.exception.game.GameErrors
import site.qgames.usergateway.publicapi.api.PublicGameApi

/**
 * Каталог игр, доступен без аутентификации (SecurityConfig). Ответы — DTO контракта game, они передаются как есть.
 * Неизвестный код игры отклоняется здесь, без вызова game.
 */
@RestController
class GameGatewayController(
    private val gameApi: GameApi,
) : PublicGameApi {

    override fun listGames(): GameListResponse = gameApi.listGames()

    override fun getGame(gameCode: String): GameDetailsResponse {
        val code = GameCode.fromCode(gameCode) ?: throw GameErrors.gameNotFound()
        return gameApi.getGame(code.code)
    }
}
