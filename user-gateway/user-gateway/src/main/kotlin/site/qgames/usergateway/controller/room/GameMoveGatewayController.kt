package site.qgames.usergateway.controller.room

import org.springframework.web.bind.annotation.RestController
import site.qgames.common.exception.exception.NotFoundException
import site.qgames.game.gatewayapi.api.GameMoveApi
import site.qgames.usergateway.mapper.room.toGame
import site.qgames.usergateway.mapper.room.toPublic
import site.qgames.usergateway.publicapi.api.PublicGameMoveApi
import site.qgames.usergateway.publicapi.dto.room.GameType
import site.qgames.usergateway.publicapi.dto.room.MoveRequest
import site.qgames.usergateway.publicapi.dto.room.RoomResponse
import java.util.UUID

/**
 * Требует аутентификации. Неизвестный код игры отклоняется здесь, без вызова game.
 */
@RestController
class GameMoveGatewayController(
    private val gameMoveApi: GameMoveApi,
) : PublicGameMoveApi {

    override fun makeMove(gameCode: String, roomId: UUID, request: MoveRequest): RoomResponse {
        val gameType = GameType.fromCode(gameCode) ?: throw NotFoundException("GAME_NOT_FOUND", "Unknown game code")
        return gameMoveApi.makeMove(gameType.toGame().code, roomId, request.toGame()).toPublic()
    }
}
