package site.qgames.game.controller.game

import org.springframework.web.bind.annotation.RestController
import site.qgames.common.web.context.RequestUserContext
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.gatewayapi.api.GameApi
import site.qgames.game.gatewayapi.dto.game.GameInfoResponse
import site.qgames.game.gatewayapi.dto.room.GameType
import site.qgames.game.mapper.game.GameInfoResponseMapper
import site.qgames.game.model.room.RoomGameType
import site.qgames.game.service.game.GameInfoService

@RestController
class GameController(
    private val gameInfoService: GameInfoService,
    private val gameInfoResponseMapper: GameInfoResponseMapper,
    private val requestUserContext: RequestUserContext,
) : GameApi {

    override fun getGame(gameCode: String): GameInfoResponse {
        // Как и остальные ручки для gateway, доступна только от имени пользователя (без X-User-Id — 401).
        requestUserContext.userId()
        val gameType = GameType.fromCode(gameCode) ?: throw RoomErrors.gameNotFound()
        return gameInfoResponseMapper.toResponse(gameInfoService.get(RoomGameType.valueOf(gameType.name)))
    }
}
