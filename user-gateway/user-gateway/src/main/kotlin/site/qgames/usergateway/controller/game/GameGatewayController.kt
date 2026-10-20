package site.qgames.usergateway.controller.game

import org.springframework.web.bind.annotation.RestController
import site.qgames.game.gatewayapi.api.GameApi
import site.qgames.usergateway.exception.game.GameErrors
import site.qgames.usergateway.mapper.game.toPublic
import site.qgames.usergateway.mapper.room.toGame
import site.qgames.usergateway.publicapi.api.PublicGameApi
import site.qgames.usergateway.publicapi.dto.game.GameInfoResponse
import site.qgames.usergateway.publicapi.dto.room.GameType

/**
 * Требует аутентификации. Неизвестный код игры отклоняется здесь, без вызова game.
 */
@RestController
class GameGatewayController(
    private val gameApi: GameApi,
) : PublicGameApi {

    override fun getGame(gameCode: String): GameInfoResponse {
        val gameType = GameType.fromCode(gameCode) ?: throw GameErrors.gameNotFound()
        return gameApi.getGame(gameType.toGame().code).toPublic()
    }
}
