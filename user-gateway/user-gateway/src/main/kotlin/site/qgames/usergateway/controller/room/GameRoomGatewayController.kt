package site.qgames.usergateway.controller.room

import org.springframework.web.bind.annotation.RestController
import site.qgames.usergateway.exception.game.GameErrors
import site.qgames.game.gatewayapi.api.GameRoomApi
import site.qgames.usergateway.mapper.game.toGame
import site.qgames.usergateway.mapper.room.toGame
import site.qgames.usergateway.mapper.room.toPublic
import site.qgames.usergateway.publicapi.api.PublicGameRoomApi
import site.qgames.usergateway.publicapi.dto.game.GameCode
import site.qgames.usergateway.publicapi.dto.room.CreateRoomRequest
import site.qgames.usergateway.publicapi.dto.room.MoveRequest
import site.qgames.usergateway.publicapi.dto.room.RoomResponse
import site.qgames.usergateway.publicapi.dto.room.RoomSettingsDto
import java.util.UUID

/**
 * Требует аутентификации. Неизвестный код игры отклоняется здесь, без вызова game. Игровые границы настроек
 * проверяет game, его ошибки по полям отдаются как есть.
 */
@RestController
class GameRoomGatewayController(
    private val gameRoomApi: GameRoomApi,
) : PublicGameRoomApi {

    override fun createRoom(gameCode: String, request: CreateRoomRequest): RoomResponse =
        gameRoomApi.createRoom(gameCode(gameCode), request.toGame()).toPublic()

    override fun updateRoomSettings(gameCode: String, roomId: UUID, request: RoomSettingsDto): RoomResponse =
        gameRoomApi.updateRoomSettings(gameCode(gameCode), roomId, request.toGame()).toPublic()

    override fun makeMove(gameCode: String, roomId: UUID, request: MoveRequest): RoomResponse =
        gameRoomApi.makeMove(gameCode(gameCode), roomId, request.toGame()).toPublic()

    /** Код игры в контракте game по публичному коду; неизвестный — 404 GAME_NOT_FOUND. */
    private fun gameCode(code: String): String =
        (GameCode.fromCode(code) ?: throw GameErrors.gameNotFound()).toGame().code
}
