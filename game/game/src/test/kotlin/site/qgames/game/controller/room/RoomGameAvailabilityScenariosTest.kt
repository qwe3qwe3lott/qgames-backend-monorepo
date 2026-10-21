package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpMethod
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.room.ChangeRoleRequest
import site.qgames.game.gatewayapi.dto.room.CreateRoomRequest
import site.qgames.game.gatewayapi.dto.room.MoveRequest
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.NOTHING_DEFAULT_SETTINGS
import java.util.stream.Stream

/**
 * Статус публикации игры и её комнаты: комнаты и партии — только у опубликованных и скрытых игр, а с комнатами
 * неопубликованной игры (NOT_PUBLISHED) работать нельзя.
 */
class RoomGameAvailabilityScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "PUBLISHED, 200, ",
        "HIDDEN, 200, ",
        "NOT_PUBLISHED, 404, GAME_NOT_FOUND",
    )
    fun `room is created only for an available game`(status: String, httpStatus: Int, code: String?) {
        setPublishStatus("nothing", status)

        createRoomRequest(ALICE, CreateRoomRequest(nothingSettings())).andExpect {
            status { isEqualTo(httpStatus) }
            if (code != null) jsonPath("$.code") { value(code) }
        }

        assertThat(countRows("room")).isEqualTo(if (code == null) 1L else 0L)
    }

    @Test
    fun `game availability is checked before the settings`() {
        setPublishStatus("nothing", "NOT_PUBLISHED")

        createRoomRequest(ALICE, CreateRoomRequest(nothingSettings(rounds = 0))).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
    }

    @Test
    fun `room of a not published game without rules is not created`() {
        createRoomRequest(ALICE, CreateRoomRequest(nothingSettings()), gameCode = "carcassonne").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
        assertThat(countRows("room")).isZero()
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "PUBLISHED, 200, ",
        "HIDDEN, 200, ",
        "NOT_PUBLISHED, 404, GAME_NOT_FOUND",
    )
    fun `game is started only for an available game`(status: String, httpStatus: Int, code: String?) {
        val roomId = roomWith(ALICE, BOB)
        setPublishStatus("nothing", status)

        call(HttpMethod.POST, "/api/rooms/$roomId/session", ALICE).andExpect {
            status { isEqualTo(httpStatus) }
            if (code != null) jsonPath("$.code") { value(code) }
        }

        assertThat(countRows("room_session")).isEqualTo(if (code == null) 1L else 0L)
    }

    @Test
    fun `room of another game is rejected by the game code in the path`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        moveRequest(ALICE, roomId, gameCode = "carcassonne").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("GAME_TYPE_MISMATCH") }
        }
        updateSettingsRequest(ALICE, roomId, nothingSettings(), gameCode = "carcassonne").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("GAME_TYPE_MISMATCH") }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roomOperations")
    fun `room of a not published game is not found`(case: String, method: HttpMethod, path: String, body: Any?) {
        val roomId = roomWith(ALICE, BOB, started = true)
        setPublishStatus("nothing", "NOT_PUBLISHED")
        val sessionBefore = sessionSnapshot()

        call(method, path.replace("{id}", roomId.toString()), ALICE, body).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }

        assertThat(sessionSnapshot()).isEqualTo(sessionBefore)
        assertThat(countRows("room_member WHERE left_at IS NULL")).isEqualTo(2)
    }

    private fun sessionSnapshot(): Map<String, Any?> =
        jdbcClient.sql("SELECT status, last_seq, move_deadline FROM room_session").query().singleRow()

    companion object {
        @JvmStatic
        fun roomOperations(): Stream<Arguments> = Stream.of(
            arguments("get", HttpMethod.GET, "/api/rooms/{id}", null),
            arguments("events", HttpMethod.GET, "/api/rooms/{id}/events", null),
            arguments("change role", HttpMethod.PUT, "/api/rooms/{id}/role", ChangeRoleRequest(RoomRole.SPECTATOR)),
            arguments("start", HttpMethod.POST, "/api/rooms/{id}/session", null),
            arguments("cancel", HttpMethod.DELETE, "/api/rooms/{id}/session", null),
            arguments("pause", HttpMethod.POST, "/api/rooms/{id}/session/pause", null),
            arguments("resume", HttpMethod.POST, "/api/rooms/{id}/session/resume", null),
            arguments("move", HttpMethod.POST, "/api/games/nothing/rooms/{id}/moves", MoveRequest(PASS)),
            arguments("update settings", HttpMethod.PUT, "/api/games/nothing/rooms/{id}/settings", NOTHING_DEFAULT_SETTINGS),
        )
    }
}
