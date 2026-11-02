package site.qgames.game.controller.session

import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MvcResult
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.game.GameCodes
import site.qgames.game.api.dto.game.GameDetailsResponse
import site.qgames.game.api.dto.room.CroissantAdvancedSettingsDto
import site.qgames.game.api.dto.room.CroissantSettingsLimitsDto
import site.qgames.game.api.dto.room.CroissantSimpleSettingsDto
import site.qgames.game.api.dto.room.CroissantTime
import site.qgames.game.api.dto.room.IntRangeDto
import site.qgames.game.api.dto.room.MoveRequest
import site.qgames.game.api.dto.room.TimeBonusType
import site.qgames.game.api.dto.room.TimeControlDto
import site.qgames.game.api.dto.session.CroissantSessionStateDto
import site.qgames.game.api.dto.session.CroissantSpotType
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CROISSANT_DESCRIPTION
import site.qgames.game.support.TestData.CROISSANT_TITLE
import site.qgames.game.support.TestData.CAROL
import site.qgames.games.core.MoveOption
import site.qgames.games.core.Session
import site.qgames.games.core.SessionStatus
import site.qgames.games.croissant.CroissantGameRules
import tools.jackson.databind.JsonNode

/**
 * В«РљСЂСѓР°СЃСЃР°РЅВ» РІ СЃРµСЂРІРёСЃРµ game: РєР°С‚Р°Р»РѕРі, РЅР°СЃС‚СЂРѕР№РєРё РєРѕРјРЅР°С‚С‹, РїР°СЂС‚РёСЏ С‡РµСЂРµР· РѕР±С‰СѓСЋ СЂСѓС‡РєСѓ С…РѕРґР° Рё РїРѕС‚РѕРє РїР°СЂС‚РёРё вЂ” СЃРЅР°РїС€РѕС‚
 * СЂР°Р·Р±РёСЂР°РµС‚СЃСЏ РІ СЃС…РµРјСѓ В«РљСЂСѓР°СЃСЃР°РЅР°В», СЃРѕР±С‹С‚РёСЏ РёРґСѓС‚ РїР°С‚С‡Р°РјРё, РѕС‚РєР°Р·С‹ РїСЂРёС…РѕРґСЏС‚ 422 `MOVE_REJECTED`.
 */
class CroissantGameScenariosTest : GameIntegrationTest() {

    @Test
    fun `croissant is available by code with its rules and default settings`() {

        val details = call(HttpMethod.GET, "/api/games/croissant", null)
            .andExpect { status { isOk() } }
            .body<GameDetailsResponse>()

        assertThat(details.code).isEqualTo(GameCode.CROISSANT)
        assertThat(details.title).isEqualTo(CROISSANT_TITLE)
        assertThat(details.description).isEqualTo(CROISSANT_DESCRIPTION)
        assertThat(details.rules.minPlayers).isEqualTo(2)
        assertThat(details.rules.maxPlayers).isEqualTo(5)
        assertThat(details.rules.timeControl.optional).isTrue()
        val limits = details.rules.gameSettingsLimits as CroissantSettingsLimitsDto
        assertThat(limits.confectioners).isEqualTo(IntRangeDto(5, 10))
        assertThat(limits.timePresets.map { it.time })
            .containsExactly(CroissantTime.CLASSIC, CroissantTime.RAPID, CroissantTime.BLITZ)
        val simple = details.rules.defaultSettings.simple as CroissantSimpleSettingsDto
        assertThat(simple.time).isEqualTo(CroissantTime.NONE)
    }

    @Test
    fun `room settings of croissant are stored and advanced ones drive the session`() {
        val advanced = CroissantAdvancedSettingsDto(GameCode.CROISSANT, 6, TIME_OFF)
        val roomId = roomWith(ALICE, BOB, settings = advanced)

        assertThat((room(roomId, ALICE).settings.advanced as CroissantAdvancedSettingsDto).confectioners).isEqualTo(6)

        start(ALICE, roomId)
        val snapshot = snapshot(openSessionStream(ALICE, roomId, gameCode = GameCodes.CROISSANT))
        val state = jsonMapper.treeToValue(snapshot.path("state"), CroissantSessionStateDto::class.java)

        assertThat(state.players).hasSize(2)
        assertThat(state.players.map { it.confectioners }).containsOnly(6)
        assertThat(state.board).hasSize(1)
        assertThat(state.tiles).hasSize(14)
        assertThat(state.deckSize).isEqualTo(71)
        assertThat(state.result).isNull()
    }

    @Test
    fun `a move places a tile, then a confectioner, and an illegal placement is rejected`() {
        val roomId = roomWith(ALICE, BOB, CAROL, started = true, settings = ADVANCED)
        val stream = openSessionStream(ALICE, roomId, gameCode = GameCodes.CROISSANT)
        val snapshot = snapshot(stream)
        assertThat(jsonMapper.treeToValue(snapshot.path("state"), CroissantSessionStateDto::class.java).board).hasSize(1)
        // Р’Р°СЂРёР°РЅС‚С‹ С…РѕРґР° РІРёРґРёС‚ С‚РѕР»СЊРєРѕ С…РѕРґСЏС‰РёР№: Р±РµСЂС‘Рј РїРѕР»РЅРѕРµ СЃРѕСЃС‚РѕСЏРЅРёРµ СЃРµСЂРІРµСЂР°.
        val full = croissantState(roomId)
        val mover = full.state.turns.currentPlayerId!!
        val option = full.state.players.single { it.id == mover }.moveOptions
            .single { it.type == CroissantGameRules.PLACE_TILE }
        val placement = placements(option).first()
        val rotations = placement["rotations"] as List<Int>
        val x = placement["x"] as Int
        val y = placement["y"] as Int

        // РќРµРєСѓРґР°: СЃС‚Р°СЂС‚РѕРІР°СЏ РєР»РµС‚РєР° СѓР¶Рµ Р·Р°РЅСЏС‚Р°.
        call(
            HttpMethod.POST,
            "/api/games/${GameCodes.CROISSANT}/rooms/$roomId/moves",
            mover,
            MoveRequest(CroissantGameRules.PLACE_TILE, mapOf("x" to 0, "y" to 0, "rotation" to 0)),
        ).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("MOVE_REJECTED") }
            jsonPath("$.reason") { value(CroissantGameRules.ERR_ILLEGAL_PLACEMENT) }
        }

        move(mover, roomId, CroissantGameRules.PLACE_TILE, mapOf("x" to x, "y" to y, "rotation" to rotations.first()))
        assertThat(awaitCodes(stream, total = 2, skip = 1)).contains(CroissantGameRules.TILE_PLACED)

        val afterTile = croissantState(roomId)
        assertThat((afterTile.state.game as site.qgames.games.croissant.CroissantGameState).board).hasSize(2)
        val pending = (afterTile.state.game as site.qgames.games.croissant.CroissantGameState).pending
        val spots = pending?.spots.orEmpty()
        if (spots.isEmpty()) {
            move(mover, roomId, CroissantGameRules.SKIP_CONFECTIONER, null)
        } else {
            move(mover, roomId, CroissantGameRules.PLACE_CONFECTIONER, mapOf("spotId" to spots.first()))
        }

        val afterStep = croissantState(roomId)
        val game = afterStep.state.game as site.qgames.games.croissant.CroissantGameState
        assertThat(game.pending).isNull()
        assertThat(afterStep.state.turns.currentPlayerId).isNotEqualTo(mover)
        assertThat(game.spots.any { it.type == site.qgames.games.croissant.CroissantSpotType.BAKERY }).isTrue()
        assertThat(game.spots.sumOf { it.confectioners.size }).isLessThanOrEqualTo(1)
        // РЎРІРµР¶РёР№ РїРѕС‚РѕРє РїРѕСЃР»Рµ С…РѕРґРѕРІ вЂ” СЃРЅР°РїС€РѕС‚ Р·Р°РЅРѕРІРѕ СЂР°Р·Р±РёСЂР°РµС‚СЃСЏ РІ СЃС…РµРјСѓ В«РљСЂСѓР°СЃСЃР°РЅР°В» (С‚РѕС‚ Р¶Рµ РјР°РїРїРµСЂ).
        val fresh = jsonMapper.treeToValue(
            snapshot(openSessionStream(mover, roomId, gameCode = GameCodes.CROISSANT)).path("state"),
            CroissantSessionStateDto::class.java,
        )
        assertThat(fresh.board).hasSize(2)
        assertThat(fresh.spots.any { it.type == CroissantSpotType.BAKERY }).isTrue()
    }

    @Test
    fun `a session of croissant runs to the end and reports a scored result`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = ADVANCED)
        val stream = openSessionStream(ALICE, roomId, gameCode = GameCodes.CROISSANT)

        var moves = 0
        while (moves < 400) {
            val session = croissantState(roomId)
            if (session.status != SessionStatus.IN_PROGRESS) break
            val mover = session.state.turns.currentPlayerId ?: break
            val options = session.state.players.single { it.id == mover }.moveOptions
            val request = nextRequest(options) ?: break
            call(
                HttpMethod.POST,
                "/api/games/${GameCodes.CROISSANT}/rooms/$roomId/moves",
                mover,
                request,
            ).andExpect { status { isNoContent() } }
            moves += 1
        }

        val session = croissantState(roomId)
        val game = session.state.game as site.qgames.games.croissant.CroissantGameState
        assertThat(session.status).isEqualTo(SessionStatus.FINISHED)
        assertThat(session.state.result).isNotNull()
        assertThat(session.state.result!!.reason).isEqualTo(CroissantGameRules.RESULT_SCORED)
        assertThat(session.state.players.sumOf { (it.game as site.qgames.games.croissant.CroissantPlayer).crumbs })
            .isGreaterThan(0)
        assertThat(game.currentTile).isNull()
        assertThat(game.board.size).isGreaterThan(1)
        assertThat(journal(roomId).map { it.second }).contains(
            CroissantGameRules.TILE_PLACED,
            CroissantGameRules.GAME_SCORED,
            "result-set",
            "session-finished",
        )
        assertThat(events(stream).map { it.first }).contains("snapshot")
    }

    @Suppress("UNCHECKED_CAST")
    private fun placements(option: MoveOption): List<Map<String, Any?>> =
        option.data!!["placements"] as List<Map<String, Any?>>

    /** РЎР»РµРґСѓСЋС‰РёР№ С…РѕРґ РїРѕ РІР°СЂРёР°РЅС‚Р°Рј РёРіСЂРѕРєР°: С‚Р°Р№Р», Р·Р°С‚РµРј РєРѕРЅРґРёС‚РµСЂ РёР»Рё РїСЂРѕРїСѓСЃРє С€Р°РіР°. */
    private fun nextRequest(options: List<MoveOption>): MoveRequest? {
        options.firstOrNull { it.type == CroissantGameRules.PLACE_TILE }?.let { option ->
            val placement = placements(option).last()
            val rotations = placement["rotations"] as List<Int>
            return MoveRequest(
                CroissantGameRules.PLACE_TILE,
                mapOf("x" to placement["x"], "y" to placement["y"], "rotation" to rotations.first()),
            )
        }
        options.firstOrNull { it.type == CroissantGameRules.PLACE_CONFECTIONER }?.let { option ->
            val spots = option.data!!["spots"] as List<String>
            return MoveRequest(CroissantGameRules.PLACE_CONFECTIONER, mapOf("spotId" to spots.first()))
        }
        return options.firstOrNull { it.type == CroissantGameRules.SKIP_CONFECTIONER }
            ?.let { MoveRequest(CroissantGameRules.SKIP_CONFECTIONER) }
    }

    private fun move(mover: UUID, roomId: UUID, type: String, data: Map<String, Any?>?) {
        call(
            HttpMethod.POST,
            "/api/games/${GameCodes.CROISSANT}/rooms/$roomId/moves",
            mover,
            MoveRequest(type, data),
        ).andExpect { status { isNoContent() } }
    }

    private fun croissantState(roomId: UUID): Session = fullState(roomId)

    private fun snapshot(stream: MvcResult): JsonNode {
        val (type, data) = awaitEvents(stream, 1).first()
        assertThat(type).isEqualTo("snapshot")
        return jsonMapper.readTree(data)
    }

    private companion object {
        val TIME_OFF = TimeControlDto(false, 1800, TimeBonusType.INCREMENT, 15)
        val ADVANCED = CroissantAdvancedSettingsDto(GameCode.CROISSANT, 7, TIME_OFF)
    }
}
