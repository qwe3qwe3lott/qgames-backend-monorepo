package site.qgames.usergateway.controller.room

import org.springframework.web.bind.annotation.RestController
import site.qgames.game.gatewayapi.api.GameRoomApi
import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.room.CreateRoomRequest
import site.qgames.game.gatewayapi.dto.room.MoveRequest
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.usergateway.exception.game.GameErrors
import site.qgames.usergateway.publicapi.api.PublicGameRoomApi
import java.util.UUID

/**
 * Требует аутентификации. Неизвестный код игры отклоняется здесь, без вызова game. Игровые границы настроек
 * проверяет game, его ошибки по полям отдаются как есть. Простое проксирование: DTO контракта game как есть.
 */
@RestController
class GameRoomGatewayController(
    private val gameRoomApi: GameRoomApi,
) : PublicGameRoomApi {

    override fun createRoom(gameCode: String, request: CreateRoomRequest): RoomResponse =
        gameRoomApi.createRoom(gameCode(gameCode), request)

    override fun updateRoomSettings(gameCode: String, roomId: UUID, request: RoomSettingsDto): RoomResponse =
        gameRoomApi.updateRoomSettings(gameCode(gameCode), roomId, request)

    override fun makeMove(gameCode: String, roomId: UUID, request: MoveRequest): RoomResponse =
        gameRoomApi.makeMove(gameCode(gameCode), roomId, request)

    /** Известный код игры; неизвестный — 404 GAME_NOT_FOUND. */
    private fun gameCode(code: String): String = (GameCode.fromCode(code) ?: throw GameErrors.gameNotFound()).code
}
