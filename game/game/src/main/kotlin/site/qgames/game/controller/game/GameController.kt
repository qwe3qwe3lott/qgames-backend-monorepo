package site.qgames.game.controller.game

import org.springframework.web.bind.annotation.RestController
import site.qgames.game.exception.room.RoomErrors
import site.qgames.game.api.web.GameApi
import site.qgames.game.api.dto.game.GameDetailsResponse
import site.qgames.game.api.dto.game.GameListResponse
import site.qgames.game.api.dto.room.GameAdvancedSettingsDto
import site.qgames.game.api.dto.room.GameSimpleSettingsDto
import site.qgames.game.mapper.game.GameResponseMapper
import site.qgames.game.mapper.room.RoomSettingsResponseMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.service.game.GameService

/** Каталог игр (пользователь не нужен: каталог доступен и гостям портала) и перевод настроек игры. */
@RestController
class GameController(
    private val gameService: GameService,
    private val gameResponseMapper: GameResponseMapper,
    private val roomSettingsResponseMapper: RoomSettingsResponseMapper,
) : GameApi {

    override fun listGames(): GameListResponse = gameResponseMapper.toListResponse(gameService.list())

    override fun getGame(gameCode: String): GameDetailsResponse = gameResponseMapper.toResponse(gameService.details(gameCode(gameCode)))

    override fun toAdvancedSettings(gameCode: String, request: GameSimpleSettingsDto): GameAdvancedSettingsDto =
        roomSettingsResponseMapper.toAdvancedResponse(
            gameService.toAdvanced(gameCode(gameCode), roomSettingsResponseMapper.toModel(request)),
        )

    private fun gameCode(gameCode: String): GameCode = GameCode.fromCode(gameCode) ?: throw RoomErrors.gameNotFound()
}
