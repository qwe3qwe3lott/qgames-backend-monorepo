package site.qgames.game.controller.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpMethod
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.game.GameDetailsResponse
import site.qgames.game.api.dto.game.GameListResponse
import site.qgames.game.api.dto.game.GameRulesDto
import site.qgames.game.api.dto.game.GameSummaryDto
import site.qgames.game.api.dto.game.TimeControlLimitsDto
import site.qgames.game.api.dto.room.IntRangeDto
import site.qgames.game.api.dto.room.NothingSettingsLimitsDto
import site.qgames.game.api.dto.room.TimeControlMode
import site.qgames.game.support.TestData.CARCASSONNE_DESCRIPTION
import site.qgames.game.support.TestData.CARCASSONNE_TITLE
import site.qgames.game.support.TestData.NOTHING_DEFAULT_SETTINGS
import site.qgames.game.support.TestData.NOTHING_DESCRIPTION
import site.qgames.game.support.TestData.NOTHING_TITLE

/**
 * Каталог игр: описание и статус — из таблицы game, параметры правил — из модуля игры. Пользователь не нужен.
 */
class GameCatalogScenariosTest : GameIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "PUBLISHED, true",
        "HIDDEN, false",
        "NOT_PUBLISHED, false",
    )
    fun `catalog shows the game depending on its publish status`(status: String, listed: Boolean) {
        setPublishStatus("nothing", status)

        val games = call(HttpMethod.GET, "/api/games", null).andExpect { status { isOk() } }.body<GameListResponse>().games

        // Каркассон не опубликован и в каталог не попадает.
        if (listed) {
            val nothing = GameSummaryDto(GameCode.NOTHING, NOTHING_TITLE, NOTHING_DESCRIPTION)
            assertThat(games).containsExactly(nothing)
        } else {
            assertThat(games).isEmpty()
        }
    }

    @Test
    fun `catalog is ordered by sort order`() {
        // Только для проверки порядка: в каталоге нужны две игры, а правила списку не нужны.
        jdbcClient.sql("UPDATE \"game#game\" SET sort_order = 1, publish_status = 'PUBLISHED' WHERE code = 'carcassonne'").update()

        call(HttpMethod.GET, "/api/games", null).andExpect {
            status { isOk() }
            jsonPath("$.games[0].code") { value("carcassonne") }
            jsonPath("$.games[0].title") { value(CARCASSONNE_TITLE) }
            jsonPath("$.games[0].description") { value(CARCASSONNE_DESCRIPTION) }
            jsonPath("$.games[1].code") { value("nothing") }
        }
    }

    @Test
    fun `game details combine the catalog row and the game rules`() {
        val details = call(HttpMethod.GET, "/api/games/nothing", null).andExpect { status { isOk() } }.body<GameDetailsResponse>()

        assertThat(details).isEqualTo(
            GameDetailsResponse(
                code = GameCode.NOTHING,
                title = NOTHING_TITLE,
                description = NOTHING_DESCRIPTION,
                rules = GameRulesDto(
                    minPlayers = 1,
                    maxPlayers = 4,
                    timeControl = TimeControlLimitsDto(listOf(TimeControlMode.NONE, TimeControlMode.PER_MOVE), IntRangeDto(5, 600)),
                    gameSettingsLimits = NothingSettingsLimitsDto(GameCode.NOTHING, IntRangeDto(1, 50)),
                    defaultSettings = NOTHING_DEFAULT_SETTINGS,
                ),
            ),
        )
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("PUBLISHED", "HIDDEN")
    fun `game details are available for an exposed game`(status: String) {
        setPublishStatus("nothing", status)

        call(HttpMethod.GET, "/api/games/nothing", null).andExpect {
            status { isOk() }
            jsonPath("$.code") { value("nothing") }
            jsonPath("$.publishStatus") { doesNotExist() }
            jsonPath("$.rules.maxPlayers") { value(4) }
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "chess, ",
        "NOTHING, ",
        "nothing, NOT_PUBLISHED",
        "carcassonne, ",
    )
    fun `game that is unknown or not exposed is not found`(code: String, status: String?) {
        status?.let { setPublishStatus(code, it) }

        call(HttpMethod.GET, "/api/games/$code", null).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
    }
}
