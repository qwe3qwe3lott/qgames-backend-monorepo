package site.qgames.game.controller.room

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import site.qgames.common.web.context.RequestUserContext
import site.qgames.game.api.dto.room.ChangeRoleRequest
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.RoomStreamEventType
import site.qgames.game.api.dto.room.TransferOwnershipRequest
import site.qgames.game.api.web.RoomApi
import site.qgames.game.config.GameProperties
import site.qgames.game.exception.room.RoomErrors
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
    private val gameProperties: GameProperties,
    /** Прокси на текущий запрос: из него читается заголовок seed (см. [seedFromHeader]). */
    private val httpRequest: HttpServletRequest,
) : RoomApi {

    override fun getRoom(roomId: UUID): RoomResponse =
        roomResponseMapper.toResponse(roomService.get(requestUserContext.userId(), roomId))

    /**
     * Поток событий комнаты (SSE). Порядок защищён seq, как у потока партии: подписчик регистрируется до транзакции
     * подключения и копит события; снапшот на seq уходит первым, следом — события новее снапшота.
     */
    override fun roomEvents(roomId: UUID): ResponseEntity<ResponseBodyEmitter> {
        val userId = requestUserContext.userId()
        val subscriber = roomStreamRegistry.register(roomId, userId)
        try {
            val connection = roomService.connect(userId, roomId)
            // С этого момента закрытие подписчика отключает пользователя (удаляет подключение, выводит из комнаты).
            subscriber.connected(connection.connectionId)
            subscriber.snapshot(
                connection.seq,
                RoomStreamEventType.SNAPSHOT.code,
                roomEventResponseMapper.toSnapshot(connection.seq, connection.details),
            )
        } catch (e: Exception) {
            // Ошибка до начала потока: подписчик убирается, а если подключение уже создано — пользователь отключается
            // (registry.discard закрывает подписчика, закрытие вызывает disconnect). Иначе остался бы эмиттер, который
            // никогда не завершится, и участник с вечно продлеваемой арендой.
            roomStreamRegistry.discard(subscriber)
            throw e
        }
        return SseResponses.stream(subscriber.emitter)
    }

    override fun changeRole(roomId: UUID, request: ChangeRoleRequest) =
        roomService.changeRole(requestUserContext.userId(), roomId, RoomRole.valueOf(request.role.name))

    override fun transferOwnership(roomId: UUID, request: TransferOwnershipRequest) =
        roomService.transferOwnership(requestUserContext.userId(), roomId, request.userId)

    override fun kickMember(roomId: UUID, userId: UUID) = roomService.kick(requestUserContext.userId(), roomId, userId)

    override fun startSession(roomId: UUID) = roomSessionService.start(requestUserContext.userId(), roomId, seedFromHeader())

    override fun cancelSession(roomId: UUID) = roomSessionService.cancel(requestUserContext.userId(), roomId)

    override fun pauseSession(roomId: UUID) = roomSessionService.pause(requestUserContext.userId(), roomId)

    override fun resumeSession(roomId: UUID) = roomSessionService.resume(requestUserContext.userId(), roomId)

    /**
     * Seed партии для воспроизводимых e2e-сценариев. Заголовок учитывается только при
     * `qgames.game.session.seed-header-enabled` (локальный стенд) и не входит в контракт, поэтому читается из запроса,
     * а не через `@RequestHeader` в [RoomApi].
     */
    private fun seedFromHeader(): Long? {
        if (!gameProperties.session.seedHeaderEnabled) return null
        val header = httpRequest.getHeader(SEED_HEADER) ?: return null
        return header.toLongOrNull() ?: throw RoomErrors.invalidSeed()
    }

    private companion object {
        const val SEED_HEADER = "X-Qgames-Seed"
    }
}
