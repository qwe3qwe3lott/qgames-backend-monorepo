package site.qgames.game.controller.session

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MvcResult
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.game.GameCodes
import site.qgames.game.api.dto.game.GameDetailsResponse
import site.qgames.game.api.dto.game.GameListResponse
import site.qgames.game.api.dto.room.CheckersSettingsDto
import site.qgames.game.api.dto.room.CheckersSettingsLimitsDto
import site.qgames.game.api.dto.room.CheckersVariant
import site.qgames.game.api.dto.room.CreateRoomRequest
import site.qgames.game.api.dto.room.MoveRequest
import site.qgames.game.api.dto.room.RoomSettingsDto
import site.qgames.game.api.dto.room.RoomStatus
import site.qgames.game.api.dto.room.TimeControlDto
import site.qgames.game.api.dto.room.TimeControlMode
import site.qgames.game.api.dto.session.CheckersColor
import site.qgames.game.api.dto.session.CheckersMoveRulesDto
import site.qgames.game.api.dto.session.CheckersSessionStateDto
import site.qgames.game.api.dto.session.MoveOptionDto
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CHECKERS_DEFAULT_SETTINGS
import site.qgames.game.support.TestData.CHECKERS_DESCRIPTION
import site.qgames.game.support.TestData.CHECKERS_TITLE
import site.qgames.games.api.result.ResultOutcome
import site.qgames.games.api.result.ResultState
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Шашки в сервисе game: каталог, настройки варианта, партия через общую ручку хода и поток
 * партии — снапшот разбирается в схему шашек, итог приходит событием result-set.
 */
class CheckersGameScenariosTest : GameIntegrationTest() {

    @Test
    fun `checkers are in the catalog and their rules are available by code`() {
        val games = call(HttpMethod.GET, "/api/games", null).andExpect { status { isOk() } }.body<GameListResponse>().games
        assertThat(games.map { it.code }).contains(GameCode.CHECKERS)

        val details = call(HttpMethod.GET, "/api/games/checkers", null).andExpect { status { isOk() } }.body<GameDetailsResponse>()

        assertThat(details.code).isEqualTo(GameCode.CHECKERS)
        assertThat(details.title).isEqualTo(CHECKERS_TITLE)
        assertThat(details.description).isEqualTo(CHECKERS_DESCRIPTION)
        assertThat(details.rules.minPlayers).isEqualTo(2)
        assertThat(details.rules.maxPlayers).isEqualTo(2)
        assertThat(details.rules.timeControl.modes).containsExactly(TimeControlMode.NONE, TimeControlMode.PER_MOVE)
        assertThat(details.rules.gameSettingsLimits).isEqualTo(CheckersSettingsLimitsDto(GameCode.CHECKERS, listOf(CheckersVariant.RUSSIAN)))
        assertThat(details.rules.defaultSettings).isEqualTo(CHECKERS_DEFAULT_SETTINGS)
    }

    @Test
    fun `variant outside the available ones is a validation error of the field`() {
        val settings = RoomSettingsDto(TimeControlDto(TimeControlMode.NONE, null), CheckersSettingsDto(GameCode.CHECKERS, CheckersVariant.INTERNATIONAL))

        createRoomRequest(ALICE, CreateRoomRequest(settings), GameCodes.CHECKERS).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors['settings.gameSettings.variant']") { exists() }
        }
    }

    @Test
    fun `a game of checkers - snapshot, move, rejections and resignation with a result`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = CHECKERS_DEFAULT_SETTINGS)
        val stream = openSessionStream(ALICE, roomId, gameCode = GameCodes.CHECKERS)

        val snapshot = snapshot(stream)
        assertThat(snapshot.path("meta").path("game").path("code").asString()).isEqualTo(GameCodes.CHECKERS)
        assertThat(jsonMapper.treeToValue(snapshot.path("meta").path("moveRules"), CheckersMoveRulesDto::class.java))
            .isEqualTo(CheckersMoveRulesDto(8, CheckersColor.WHITE, false, true, true, true, false, true, true))
        val state = jsonMapper.treeToValue(snapshot.path("state"), CheckersSessionStateDto::class.java)
        assertThat(state.pieces).hasSize(24)
        assertThat(state.result).isNull()
        assertThat(state.lastMove).isNull()
        val white = state.players.single { it.color == CheckersColor.WHITE }.id
        val black = state.players.single { it.color == CheckersColor.BLACK }.id
        assertThat(state.turns.currentPlayerId).isEqualTo(white)
        val aliceOptions = if (ALICE == white) listOf(MoveOptionDto("MOVE"), MoveOptionDto("RESIGN")) else listOf(MoveOptionDto("RESIGN"))
        assertThat(state.players.single { it.id == ALICE }.moveOptions).isEqualTo(aliceOptions)

        moveRequest(white, roomId, mapOf("path" to "a1")).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }
        rejected(white, roomId, listOf(listOf(3, 3), listOf(5, 5)), "ILLEGAL_MOVE")
        rejected(black, roomId, listOf(listOf(6, 6), listOf(5, 5)), "NOT_YOUR_TURN")
        moveRequest(white, roomId, mapOf("path" to listOf(listOf(3, 3), listOf(4, 4)))).andExpect { status { isNoContent() } }

        val afterMove = awaitEvents(stream, 6).drop(1).map { jsonMapper.readTree(it.second) }
        assertThat(afterMove.map { it.path("code").asString() })
            .containsExactly("piece-moved", "last-move-changed", "turn-changed", "move-options-changed", "move-options-changed")

        // Сдаться можно вне очереди: белые сдаются на ходу чёрных.
        call(HttpMethod.POST, "/api/games/checkers/rooms/$roomId/moves", white, MoveRequest("RESIGN")).andExpect { status { isNoContent() } }

        val ending = awaitEvents(stream, 11).drop(6).map { it.first to jsonMapper.readTree(it.second) }
        assertThat(ending.map { it.second.path("code").asString() })
            .containsExactly("player-resigned", "result-set", "move-options-changed", "move-options-changed", "session-finished")
        assertThat(ending[1].first).isEqualTo("put")
        assertThat(ending[1].second.path("data").path("value")).isEqualTo(
            jsonMapper.readTree("""{"outcome": "WIN", "winnerIds": ["$black"], "reason": "RESIGN"}"""),
        )
        assertThat(fullState(roomId).state.result).isEqualTo(ResultState(ResultOutcome.WIN, listOf(black), "RESIGN"))
        assertThat(room(roomId, ALICE).status).isEqualTo(RoomStatus.WAITING)
    }

    private fun moveRequest(user: UUID, roomId: UUID, data: Map<String, Any?>) =
        call(HttpMethod.POST, "/api/games/checkers/rooms/$roomId/moves", user, MoveRequest("MOVE", data))

    private fun rejected(user: UUID, roomId: UUID, path: List<List<Int>>, reason: String) {
        moveRequest(user, roomId, mapOf("path" to path)).andExpect {
            status { isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value()) }
            jsonPath("$.code") { value("MOVE_REJECTED") }
            jsonPath("$.reason") { value(reason) }
        }
    }

    private fun snapshot(stream: MvcResult): JsonNode {
        val (type, data) = awaitEvents(stream, 1).first()
        assertThat(type).isEqualTo("snapshot")
        return jsonMapper.readTree(data)
    }
}
