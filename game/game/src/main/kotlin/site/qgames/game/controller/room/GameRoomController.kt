package site.qgames.game.controller.room

import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import site.qgames.common.web.context.RequestUserContext
import site.qgames.game.api.dto.room.MoveRequest
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.UpdateRoomSettingsRequest
import site.qgames.game.api.dto.session.SessionStreamEventTypes
import site.qgames.game.api.web.GameRoomApi
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.mapper.room.RoomResponseMapper
import site.qgames.game.mapper.room.RoomSettingsResponseMapper
import site.qgames.game.mapper.session.SessionEventResponseMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.service.room.RoomService
import site.qgames.game.service.session.RoomSessionService
import site.qgames.game.service.session.SessionStreamService
import site.qgames.game.stream.common.SseResponses
import site.qgames.game.stream.session.SessionStreamRegistry
import site.qgames.games.core.SessionEngine
import site.qgames.games.core.SessionStatus

@RestController
class GameRoomController(
    private val roomService: RoomService,
    private val roomSessionService: RoomSessionService,
    private val roomResponseMapper: RoomResponseMapper,
    private val roomSettingsResponseMapper: RoomSettingsResponseMapper,
    private val sessionStreamRegistry: SessionStreamRegistry,
    private val sessionStreamService: SessionStreamService,
    private val sessionEventResponseMapper: SessionEventResponseMapper,
    private val requestUserContext: RequestUserContext,
) : GameRoomApi {

    override fun createRoom(gameCode: String): RoomResponse =
        roomResponseMapper.toResponse(
            roomService.create(gameCode(gameCode)),
        )

    override fun updateRoomSettings(gameCode: String, roomId: UUID, request: UpdateRoomSettingsRequest) =
        roomService.updateSettings(
            userId = requestUserContext.userId(),
            gameCode = gameCode(gameCode),
            roomId = roomId,
            update = roomSettingsResponseMapper.toModel(request),
        )

    override fun makeMove(gameCode: String, roomId: UUID, request: MoveRequest) =
        roomSessionService.move(
            userId = requestUserContext.userId(),
            gameCode = gameCode(gameCode),
            roomId = roomId,
            type = request.type,
            data = request.data,
        )

    /**
     * Поток событий партии (SSE). Порядок подключения: проверка доступа (ошибки — обычным ProblemDetail) → регистрация
     * подписчика → снапшот зрителя на последнем seq N (кеш + доигрывание) → события, пришедшие раньше снапшота, с
     * seq ≤ N отбрасываются.
     */
    override fun sessionEvents(gameCode: String, roomId: UUID, sessionId: UUID): ResponseEntity<ResponseBodyEmitter> {
        val subscription = sessionStreamService.subscribe(requestUserContext.userId(), gameCode(gameCode), roomId, sessionId)
        val subscriber = sessionStreamRegistry.register(sessionId, subscription.viewer)
        try {
            val snapshot = sessionStreamService.snapshot(sessionId, subscription.viewer) ?: throw RoomErrors.sessionNotFound()
            subscriber.snapshot(
                seq = snapshot.state.seq,
                type = SessionStreamEventTypes.SNAPSHOT,
                data = sessionEventResponseMapper.toSnapshot(snapshot),
                last = snapshot.state.document[SessionEngine.STATUS] != SessionStatus.IN_PROGRESS.name,
            )
        } catch (e: Exception) {
            subscriber.close()
            throw e
        }
        return SseResponses.stream(subscriber.emitter)
    }

    private fun gameCode(gameCode: String): GameCode = GameCode.fromCode(gameCode) ?: throw RoomErrors.gameNotFound()
}
