package site.qgames.game.controller.room

import org.springframework.web.bind.annotation.RestController
import site.qgames.common.web.context.RequestUserContext
import site.qgames.game.model.room.RoomRole
import site.qgames.game.gatewayapi.api.RoomApi
import site.qgames.game.gatewayapi.dto.room.ChangeRoleRequest
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.mapper.room.RoomResponseMapper
import site.qgames.game.service.room.RoomService
import site.qgames.game.service.session.RoomSessionService
import java.util.UUID

@RestController
class RoomController(
    private val roomService: RoomService,
    private val roomSessionService: RoomSessionService,
    private val roomResponseMapper: RoomResponseMapper,
    private val requestUserContext: RequestUserContext,
) : RoomApi {

    override fun getRoom(roomId: UUID): RoomResponse =
        roomResponseMapper.toResponse(roomService.get(requestUserContext.userId(), roomId))

    override fun joinRoom(roomId: UUID): RoomResponse =
        roomResponseMapper.toResponse(roomService.join(requestUserContext.userId(), roomId))

    override fun leaveRoom(roomId: UUID) =
        roomService.leave(requestUserContext.userId(), roomId)

    override fun changeRole(roomId: UUID, request: ChangeRoleRequest): RoomResponse =
        roomResponseMapper.toResponse(roomService.changeRole(requestUserContext.userId(), roomId, RoomRole.valueOf(request.role.name)))

    override fun deleteRoom(roomId: UUID) =
        roomService.delete(requestUserContext.userId(), roomId)

    override fun startSession(roomId: UUID): RoomResponse =
        roomResponseMapper.toResponse(roomSessionService.start(requestUserContext.userId(), roomId))

    override fun cancelSession(roomId: UUID): RoomResponse =
        roomResponseMapper.toResponse(roomSessionService.cancel(requestUserContext.userId(), roomId))

    override fun pauseSession(roomId: UUID): RoomResponse =
        roomResponseMapper.toResponse(roomSessionService.pause(requestUserContext.userId(), roomId))

    override fun resumeSession(roomId: UUID): RoomResponse =
        roomResponseMapper.toResponse(roomSessionService.resume(requestUserContext.userId(), roomId))
}
