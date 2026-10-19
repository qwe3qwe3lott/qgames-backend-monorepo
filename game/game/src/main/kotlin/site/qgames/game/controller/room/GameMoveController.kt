package site.qgames.game.controller.room

import org.springframework.web.bind.annotation.RestController
import site.qgames.common.web.context.RequestUserContext
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.gatewayapi.api.GameMoveApi
import site.qgames.game.gatewayapi.dto.room.GameType
import site.qgames.game.gatewayapi.dto.room.MoveRequest
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.mapper.room.RoomResponseMapper
import site.qgames.game.model.room.RoomGameType
import site.qgames.game.service.session.RoomSessionService
import java.util.UUID

@RestController
class GameMoveController(
    private val roomSessionService: RoomSessionService,
    private val roomResponseMapper: RoomResponseMapper,
    private val requestUserContext: RequestUserContext,
) : GameMoveApi {

    override fun makeMove(gameCode: String, roomId: UUID, request: MoveRequest): RoomResponse {
        val gameType = GameType.fromCode(gameCode) ?: throw RoomErrors.gameNotFound()
        return roomResponseMapper.toResponse(
            roomSessionService.move(
                userId = requestUserContext.userId(),
                gameType = RoomGameType.valueOf(gameType.name),
                roomId = roomId,
                type = request.type,
                data = request.data,
                expectedVersion = request.expectedVersion,
            ),
        )
    }
}
