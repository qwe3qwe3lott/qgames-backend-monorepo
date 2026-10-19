package site.qgames.usergateway.controller.room

import org.springframework.web.bind.annotation.RestController
import site.qgames.game.gatewayapi.api.RoomApi
import site.qgames.usergateway.mapper.room.toGame
import site.qgames.usergateway.mapper.room.toPublic
import site.qgames.usergateway.publicapi.api.PublicRoomApi
import site.qgames.usergateway.publicapi.dto.room.ChangeRoleRequest
import site.qgames.usergateway.publicapi.dto.room.CreateRoomRequest
import site.qgames.usergateway.publicapi.dto.room.RoomResponse
import java.util.UUID

/**
 * Требует аутентификации. X-User-Id в game проставляет UserIdPropagationInterceptor.
 */
@RestController
class RoomGatewayController(
    private val roomApi: RoomApi,
) : PublicRoomApi {

    override fun createRoom(request: CreateRoomRequest): RoomResponse = roomApi.createRoom(request.toGame()).toPublic()

    override fun getRoom(roomId: UUID): RoomResponse = roomApi.getRoom(roomId).toPublic()

    override fun joinRoom(roomId: UUID): RoomResponse = roomApi.joinRoom(roomId).toPublic()

    override fun leaveRoom(roomId: UUID) = roomApi.leaveRoom(roomId)

    override fun changeRole(roomId: UUID, request: ChangeRoleRequest): RoomResponse =
        roomApi.changeRole(roomId, request.toGame()).toPublic()

    override fun deleteRoom(roomId: UUID) = roomApi.deleteRoom(roomId)

    override fun startSession(roomId: UUID): RoomResponse = roomApi.startSession(roomId).toPublic()

    override fun cancelSession(roomId: UUID): RoomResponse = roomApi.cancelSession(roomId).toPublic()
}
