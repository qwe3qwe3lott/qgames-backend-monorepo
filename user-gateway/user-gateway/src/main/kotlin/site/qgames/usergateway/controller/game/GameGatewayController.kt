package site.qgames.usergateway.controller.game

import org.springframework.web.bind.annotation.RestController
import site.qgames.game.gatewayapi.api.GameApi
import site.qgames.usergateway.exception.game.GameErrors
import site.qgames.usergateway.mapper.game.toGame
import site.qgames.usergateway.mapper.game.toPublic
import site.qgames.usergateway.publicapi.api.PublicGameApi
import site.qgames.usergateway.publicapi.dto.game.GameCode
import site.qgames.usergateway.publicapi.dto.game.GameDetailsResponse
import site.qgames.usergateway.publicapi.dto.game.GameListResponse

/**
 * Каталог игр, доступен без аутентификации (SecurityConfig). Неизвестный код игры отклоняется здесь, без вызова game.
 */
@RestController
class GameGatewayController(
    private val gameApi: GameApi,
) : PublicGameApi {

    override fun listGames(): GameListResponse = gameApi.listGames().toPublic()

    override fun getGame(gameCode: String): GameDetailsResponse {
        val code = GameCode.fromCode(gameCode) ?: throw GameErrors.gameNotFound()
        return gameApi.getGame(code.toGame().code).toPublic()
    }
}
