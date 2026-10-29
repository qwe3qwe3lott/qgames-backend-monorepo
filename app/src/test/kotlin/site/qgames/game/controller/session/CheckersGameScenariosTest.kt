package site.qgames.game.controller.session

import java.util.UUID
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
import site.qgames.game.api.dto.game.TimeControlLimitsDto
import site.qgames.game.api.dto.room.CheckersAdvancedSettingsDto
import site.qgames.game.api.dto.room.CheckersRulePresetDto
import site.qgames.game.api.dto.room.CheckersSettingsLimitsDto
import site.qgames.game.api.dto.room.CheckersTime
import site.qgames.game.api.dto.room.CheckersTimePresetDto
import site.qgames.game.api.dto.room.CheckersVariant
import site.qgames.game.api.dto.room.IntRangeDto
import site.qgames.game.api.dto.room.MoveRequest
import site.qgames.game.api.dto.room.RoomStatus
import site.qgames.game.api.dto.room.UpdateRoomSettingsRequest
import site.qgames.game.api.dto.session.CheckersColor
import site.qgames.game.api.dto.session.CheckersAdvancedRulesDto
import site.qgames.game.api.dto.session.CheckersSessionStateDto
import site.qgames.game.api.dto.session.MoveOptionDto
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CHECKERS_DEFAULT_SETTINGS
import site.qgames.game.support.TestData.CHECKERS_DESCRIPTION
import site.qgames.game.support.TestData.CHECKERS_TITLE
import site.qgames.game.support.TestData.checkersAdvanced
import site.qgames.games.core.ResultOutcome
import site.qgames.games.core.ResultState
import tools.jackson.databind.JsonNode

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
        assertThat(details.rules.timeControl).isEqualTo(TimeControlLimitsDto(optional = true, IntRangeDto(60, 10800), IntRangeDto(0, 60)))
        val limits = details.rules.gameSettingsLimits as CheckersSettingsLimitsDto
        assertThat(limits.variants).containsExactly(CheckersVariant.RUSSIAN, CheckersVariant.INTERNATIONAL, CheckersVariant.ENGLISH)
        assertThat(limits.timePresets).hasSize(9)
        assertThat(limits.timePresets.first()).isEqualTo(CheckersTimePresetDto(CheckersVariant.RUSSIAN, CheckersTime.CLASSIC, 1800, 30))
        assertThat(limits.boardSizes).containsExactly(8, 10)
        assertThat(limits.drawQuietMoves).containsExactly(30, 50, 80)
        assertThat(limits.rulePresets.map { it.variant }).containsExactly(CheckersVariant.RUSSIAN, CheckersVariant.INTERNATIONAL, CheckersVariant.ENGLISH)
        // Пресет варианта — те же правила, что simple выводит в advanced.
        CheckersVariant.entries.forEach { variant ->
            val preset = limits.rulePresets.single { it.variant == variant }
            val advanced = checkersAdvanced(variant)
            assertThat(preset).isEqualTo(
                CheckersRulePresetDto(
                    variant, advanced.boardSize, advanced.firstColor, advanced.endgameRule, advanced.drawQuietMoves,
                    advanced.menMoveBackward, advanced.menCaptureBackward, advanced.flyingKings, advanced.captureRequired,
                    advanced.maxCaptureRequired, advanced.promoteMidCapture, advanced.removeCapturedAfterMove,
                ),
            )
        }
        assertThat(details.rules.defaultSettings).isEqualTo(CHECKERS_DEFAULT_SETTINGS)
    }

    @Test
    fun `advanced board size and draw counter outside the limits are rejected`() {
        val roomId = roomWith(ALICE, settings = CHECKERS_DEFAULT_SETTINGS.advanced)
        val advanced = checkersAdvanced(CheckersVariant.RUSSIAN).copy(boardSize = 12, drawQuietMoves = 7)

        updateSettingsRequest(ALICE, roomId, UpdateRoomSettingsRequest(advanced = advanced), GameCodes.CHECKERS).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors['advanced.boardSize']") { exists() }
            jsonPath("$.errors['advanced.drawQuietMoves']") { exists() }
        }
    }

    @Test
    fun `advanced settings of the room set the rules and the board of the game`() {
        val settings = checkersAdvanced(CheckersVariant.RUSSIAN).copy(boardSize = 10, firstColor = CheckersColor.BLACK, removeCapturedAfterMove = false)
        val roomId = roomWith(ALICE, BOB, started = true, settings = settings)
        val stream = openSessionStream(ALICE, roomId, gameCode = GameCodes.CHECKERS)

        val snapshot = snapshot(stream)
        assertThat(jsonMapper.treeToValue(snapshot.path("meta").path("advancedRules"), CheckersAdvancedRulesDto::class.java))
            .isEqualTo(CheckersAdvancedRulesDto(10, CheckersColor.BLACK, false, true, true, true, false, true, false))
        assertThat(jsonMapper.treeToValue(snapshot.path("meta").path("game").path("settings"), CheckersAdvancedSettingsDto::class.java))
            .isEqualTo(settings)
        assertThat(jsonMapper.treeToValue(snapshot.path("state"), CheckersSessionStateDto::class.java).pieces).hasSize(40)
    }

    @Test
    fun `a game of checkers - snapshot, move, rejections and resignation with a result`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = CHECKERS_DEFAULT_SETTINGS.advanced)
        val stream = openSessionStream(ALICE, roomId, gameCode = GameCodes.CHECKERS)

        val snapshot = snapshot(stream)
        assertThat(snapshot.path("meta").path("game").path("code").asString()).isEqualTo(GameCodes.CHECKERS)
        assertThat(jsonMapper.treeToValue(snapshot.path("meta").path("advancedRules"), CheckersAdvancedRulesDto::class.java))
            .isEqualTo(CheckersAdvancedRulesDto(8, CheckersColor.WHITE, false, true, true, true, false, true, true))
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

        val afterMove = awaitEvents(stream, 9).drop(1).map { jsonMapper.readTree(it.second) }
        assertThat(afterMove.map { it.path("code").asString() }).containsExactly(
            "piece-moved", "last-move-changed", "counters-changed", "position-recorded", "turn-changed",
            "move-options-changed", "move-options-changed", "clock-switched",
        )

        // Сдаться можно вне очереди: белые сдаются на ходу чёрных.
        call(HttpMethod.POST, "/api/games/checkers/rooms/$roomId/moves", white, MoveRequest("RESIGN")).andExpect { status { isNoContent() } }

        val ending = awaitEvents(stream, 15).drop(9).map { it.first to jsonMapper.readTree(it.second) }
        assertThat(ending.map { it.second.path("code").asString() })
            .containsExactly("player-resigned", "result-set", "move-options-changed", "move-options-changed", "timer-stopped", "session-finished")
        assertThat(ending[1].first).isEqualTo("put")
        assertThat(ending[1].second.path("data").path("value")).isEqualTo(
            jsonMapper.readTree("""{"outcome": "WIN", "winnerIds": ["$black"], "reason": "RESIGN"}"""),
        )
        assertThat(fullState(roomId).state.result).isEqualTo(ResultState(ResultOutcome.WIN, listOf(black), "RESIGN"))
        assertThat(room(roomId, ALICE).status).isEqualTo(RoomStatus.WAITING)
    }

    @Test
    fun `a draw offered with a move and accepted by the opponent ends the game`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = CHECKERS_DEFAULT_SETTINGS.advanced)
        val players = fullState(roomId).state.players
        val white = players.single { (it.game as site.qgames.games.checkers.CheckersPlayer).color.name == "WHITE" }.id
        val black = players.single { it.id != white }.id

        rejectedRequest(black, roomId, MoveRequest("ACCEPT_DRAW"), "NOT_YOUR_TURN")
        moveRequest(white, roomId, mapOf("path" to listOf(listOf(3, 3), listOf(4, 4)), "offerDraw" to true)).andExpect { status { isNoContent() } }
        call(HttpMethod.POST, "/api/games/checkers/rooms/$roomId/moves", black, MoveRequest("ACCEPT_DRAW")).andExpect { status { isNoContent() } }

        assertThat(fullState(roomId).state.result).isEqualTo(ResultState(ResultOutcome.DRAW, emptyList(), "DRAW_AGREED"))
    }

    private fun rejectedRequest(user: UUID, roomId: UUID, move: MoveRequest, reason: String) {
        call(HttpMethod.POST, "/api/games/checkers/rooms/$roomId/moves", user, move).andExpect {
            status { isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value()) }
            jsonPath("$.reason") { value(reason) }
        }
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
