package site.qgames.game.controller.room

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import site.qgames.common.web.context.RequestUserContext
import site.qgames.game.api.dto.room.ChangeRoleRequest
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.RoomStreamEventType
import site.qgames.game.api.dto.room.TransferOwnershipRequest
import site.qgames.game.api.web.RoomApi
import site.qgames.game.mapper.room.RoomEventResponseMapper
import site.qgames.game.mapper.room.RoomResponseMapper
import site.qgames.game.model.room.RoomRole
import site.qgames.game.service.room.RoomService
import site.qgames.game.service.session.RoomSessionService
import site.qgames.game.stream.common.SseResponses
import site.qgames.game.stream.room.RoomStreamRegistry
import java.util.UUID

@RestController
class RoomController(
    private val roomService: RoomService,
    private val roomSessionService: RoomSessionService,
    private val roomResponseMapper: RoomResponseMapper,
    private val roomEventResponseMapper: RoomEventResponseMapper,
    private val roomStreamRegistry: RoomStreamRegistry,
    private val requestUserContext: RequestUserContext,
) : RoomApi {

    override fun getRoom(roomId: UUID): RoomResponse =
        roomResponseMapper.toResponse(roomService.get(requestUserContext.userId(), roomId))

    /**
     * Поток событий комнаты (SSE). Порядок подключения защищает от гонки снапшота и событий (у событий комнаты нет
     * seq): подписчик регистрируется до транзакции подключения и отбрасывает события до своего маркера; маркер
     * публикуется в той же транзакции после чтения снапшота; снапшот уходит первым, следом — события после маркера.
     */
    override fun roomEvents(roomId: UUID): ResponseEntity<ResponseBodyEmitter> {
        val userId = requestUserContext.userId()
        val subscriber = roomStreamRegistry.register(roomId, userId)
        try {
            val connection = roomService.connect(userId, roomId, subscriber.id)
            // С этого момента закрытие подписчика отключает пользователя (удаляет подключение, выводит из комнаты).
            subscriber.connected(connection.connectionId)
            subscriber.snapshot(RoomStreamEventType.SNAPSHOT.code, roomEventResponseMapper.toSnapshot(connection.details))
        } catch (e: Exception) {
            // Ошибка до начала потока: подписчик убирается, а если подключение уже создано — пользователь отключается
            // (registry.discard закрывает подписчика, закрытие вызывает disconnect). Иначе остался бы эмиттер, который
            // никогда не завершится, и участник с вечно продлеваемой арендой.
            roomStreamRegistry.discard(subscriber)
            throw e
        }
        return SseResponses.stream(subscriber.emitter)
    }

    override fun changeRole(roomId: UUID, request: ChangeRoleRequest): RoomResponse =
        roomResponseMapper.toResponse(roomService.changeRole(requestUserContext.userId(), roomId, RoomRole.valueOf(request.role.name)))

    override fun transferOwnership(roomId: UUID, request: TransferOwnershipRequest): RoomResponse =
        roomResponseMapper.toResponse(roomService.transferOwnership(requestUserContext.userId(), roomId, request.userId))

    override fun startSession(roomId: UUID): RoomResponse =
        roomResponseMapper.toResponse(roomSessionService.start(requestUserContext.userId(), roomId))

    override fun cancelSession(roomId: UUID) = roomSessionService.cancel(requestUserContext.userId(), roomId)

    override fun pauseSession(roomId: UUID) = roomSessionService.pause(requestUserContext.userId(), roomId)

    override fun resumeSession(roomId: UUID) = roomSessionService.resume(requestUserContext.userId(), roomId)
}
