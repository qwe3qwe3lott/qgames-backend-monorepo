package site.qgames.game.controller.game

import org.springframework.web.bind.annotation.RestController
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.gatewayapi.api.GameApi
import site.qgames.game.gatewayapi.dto.game.GameDetailsResponse
import site.qgames.game.gatewayapi.dto.game.GameListResponse
import site.qgames.game.mapper.game.GameResponseMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.service.game.GameService

/** Каталог игр. Пользователь не нужен: каталог доступен и гостям портала. */
@RestController
class GameController(
    private val gameService: GameService,
    private val gameResponseMapper: GameResponseMapper,
) : GameApi {

    override fun listGames(): GameListResponse = gameResponseMapper.toListResponse(gameService.list())

    override fun getGame(gameCode: String): GameDetailsResponse {
        val code = GameCode.fromCode(gameCode) ?: throw RoomErrors.gameNotFound()
        return gameResponseMapper.toResponse(gameService.details(code))
    }
}
