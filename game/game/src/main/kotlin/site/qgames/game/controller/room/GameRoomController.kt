package site.qgames.game.controller.room

import org.springframework.web.bind.annotation.RestController
import site.qgames.common.web.context.RequestUserContext
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.gatewayapi.api.GameRoomApi
import site.qgames.game.gatewayapi.dto.room.CreateRoomRequest
import site.qgames.game.gatewayapi.dto.room.MoveRequest
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.game.mapper.room.RoomResponseMapper
import site.qgames.game.mapper.room.RoomSettingsResponseMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.service.room.RoomService
import site.qgames.game.service.session.RoomSessionService
import java.util.UUID

@RestController
class GameRoomController(
    private val roomService: RoomService,
    private val roomSessionService: RoomSessionService,
    private val roomResponseMapper: RoomResponseMapper,
    private val roomSettingsResponseMapper: RoomSettingsResponseMapper,
    private val requestUserContext: RequestUserContext,
) : GameRoomApi {

    override fun createRoom(gameCode: String, request: CreateRoomRequest): RoomResponse =
        roomResponseMapper.toResponse(
            roomService.create(requestUserContext.userId(), gameCode(gameCode), roomSettingsResponseMapper.toModel(request.settings)),
        )

    override fun updateRoomSettings(gameCode: String, roomId: UUID, request: RoomSettingsDto): RoomResponse =
        roomResponseMapper.toResponse(
            roomService.updateSettings(
                userId = requestUserContext.userId(),
                gameCode = gameCode(gameCode),
                roomId = roomId,
                settings = roomSettingsResponseMapper.toModel(request),
            ),
        )

    override fun makeMove(gameCode: String, roomId: UUID, request: MoveRequest): RoomResponse =
        roomResponseMapper.toResponse(
            roomSessionService.move(
                userId = requestUserContext.userId(),
                gameCode = gameCode(gameCode),
                roomId = roomId,
                type = request.type,
                data = request.data,
                expectedVersion = request.expectedVersion,
            ),
        )

    private fun gameCode(gameCode: String): GameCode = GameCode.fromCode(gameCode) ?: throw RoomErrors.gameNotFound()
}
