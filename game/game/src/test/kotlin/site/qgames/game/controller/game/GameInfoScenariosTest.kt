package site.qgames.game.controller.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.game.GameInfoResponse
import site.qgames.game.gatewayapi.dto.game.TimeControlLimitsDto
import site.qgames.game.gatewayapi.dto.room.GameType
import site.qgames.game.gatewayapi.dto.room.IntRangeDto
import site.qgames.game.gatewayapi.dto.room.NothingSettingsLimitsDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.NOTHING_DEFAULT_SETTINGS

/** Параметры игры для формы настроек: строятся из правил игры. */
class GameInfoScenariosTest : BaseIntegrationTest() {

    @Test
    fun `game parameters come from the game rules`() {
        val info = call(HttpMethod.GET, "/api/games/nothing", ALICE).andExpect { status { isOk() } }.body<GameInfoResponse>()

        assertThat(info).isEqualTo(
            GameInfoResponse(
                gameType = GameType.NOTHING,
                minPlayers = 1,
                maxPlayers = 4,
                timeControl = TimeControlLimitsDto(listOf(TimeControlMode.NONE, TimeControlMode.PER_MOVE), IntRangeDto(5, 600)),
                gameSettingsLimits = NothingSettingsLimitsDto(GameType.NOTHING, IntRangeDto(1, 50)),
                defaultSettings = NOTHING_DEFAULT_SETTINGS,
            ),
        )
    }

    @Test
    fun `unknown game is not found`() {
        call(HttpMethod.GET, "/api/games/chess", ALICE).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
    }

    @Test
    fun `game parameters require a user`() {
        call(HttpMethod.GET, "/api/games/nothing", null).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }
}
