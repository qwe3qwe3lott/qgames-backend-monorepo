package site.qgames.usergateway.controller.room

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import site.qgames.game.gatewayapi.api.RoomApi
import site.qgames.game.gatewayapi.api.RoomEventsApi
import site.qgames.game.gatewayapi.dto.room.ChangeRoleRequest
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.usergateway.publicapi.api.PublicRoomApi
import site.qgames.usergateway.stream.SseStreamProxy
import java.util.UUID

/**
 * Требует аутентификации. X-User-Id в game проставляет UserIdPropagationInterceptor. Простое проксирование: запросы
 * и ответы — DTO контракта game как есть.
 */
@RestController
class RoomGatewayController(
    private val roomApi: RoomApi,
    private val roomEventsApi: RoomEventsApi,
    private val sseStreamProxy: SseStreamProxy,
) : PublicRoomApi {

    override fun getRoom(roomId: UUID): RoomResponse = roomApi.getRoom(roomId)

    /** Поток game пересылается байтами как есть ([SseStreamProxy]). JWT проверяется только при подключении. */
    override fun roomEvents(roomId: UUID): ResponseEntity<ResponseBodyEmitter> =
        sseStreamProxy.forward("room-events-proxy-$roomId", roomEventsApi.roomEvents(roomId))

    override fun changeRole(roomId: UUID, request: ChangeRoleRequest): RoomResponse = roomApi.changeRole(roomId, request)

    override fun startSession(roomId: UUID): RoomResponse = roomApi.startSession(roomId)

    override fun cancelSession(roomId: UUID) = roomApi.cancelSession(roomId)

    override fun pauseSession(roomId: UUID) = roomApi.pauseSession(roomId)

    override fun resumeSession(roomId: UUID) = roomApi.resumeSession(roomId)
}
