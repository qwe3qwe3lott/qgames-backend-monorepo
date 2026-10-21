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
import site.qgames.game.gatewayapi.dto.room.RoomStatus
import site.qgames.game.gatewayapi.dto.room.SessionStatus
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.support.TestData.NOTHING_DEFAULT_SETTINGS
import java.util.stream.Stream

/**
 * Статус публикации игры и её комнаты: новые комнаты и партии — только у опубликованных и скрытых игр, идущие
 * партии временно недоступной игры продолжаются, а с комнатами неопубликованной и принудительно
 * отключённой игры работать нельзя.
 */
class RoomGameAvailabilityScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "PUBLISHED, 200, ",
        "HIDDEN, 200, ",
        "DISABLED, 409, GAME_UNAVAILABLE",
        "NOT_PUBLISHED, 404, GAME_NOT_FOUND",
        "FORCE_DISABLED, 404, GAME_NOT_FOUND",
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
        setPublishStatus("nothing", "DISABLED")

        createRoomRequest(ALICE, CreateRoomRequest(nothingSettings(rounds = 0))).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("GAME_UNAVAILABLE") }
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
        "DISABLED, 409, GAME_UNAVAILABLE",
        "NOT_PUBLISHED, 404, GAME_NOT_FOUND",
        "FORCE_DISABLED, 404, GAME_NOT_FOUND",
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
    fun `running game of a temporarily disabled game goes on`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(rounds = 1))
        setPublishStatus("nothing", "DISABLED")

        pass(ALICE, roomId)
        pass(BOB, roomId)
        val finished = room(roomId, ALICE)

        assertThat(finished.status).isEqualTo(RoomStatus.WAITING)
        assertThat(finished.session!!.status).isEqualTo(SessionStatus.FINISHED)
        // Новая партия уже не начинается, а комната продолжает работать.
        call(HttpMethod.POST, "/api/rooms/$roomId/session", ALICE).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("GAME_UNAVAILABLE") }
        }
        assertThat(join(CAROL, roomId).members.map { it.userId }).contains(CAROL)
        updateSettingsRequest(ALICE, roomId, nothingSettings(rounds = 2)).andExpect { status { isOk() } }
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

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("roomOperations")
    fun `room of a game that is not exposed is not found`(status: String, case: String, method: HttpMethod, path: String, body: Any?) {
        val roomId = roomWith(ALICE, BOB, started = true)
        setPublishStatus("nothing", status)
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
        fun roomOperations(): Stream<Arguments> = listOf("NOT_PUBLISHED", "FORCE_DISABLED").flatMap { status ->
            listOf(
                arguments(status, "get", HttpMethod.GET, "/api/rooms/{id}", null),
                arguments(status, "events", HttpMethod.GET, "/api/rooms/{id}/events", null),
                arguments(status, "change role", HttpMethod.PUT, "/api/rooms/{id}/role", ChangeRoleRequest(RoomRole.SPECTATOR)),
                arguments(status, "start", HttpMethod.POST, "/api/rooms/{id}/session", null),
                arguments(status, "cancel", HttpMethod.DELETE, "/api/rooms/{id}/session", null),
                arguments(status, "pause", HttpMethod.POST, "/api/rooms/{id}/session/pause", null),
                arguments(status, "resume", HttpMethod.POST, "/api/rooms/{id}/session/resume", null),
                arguments(status, "move", HttpMethod.POST, "/api/games/nothing/rooms/{id}/moves", MoveRequest(PASS)),
                arguments(status, "update settings", HttpMethod.PUT, "/api/games/nothing/rooms/{id}/settings", NOTHING_DEFAULT_SETTINGS),
            )
        }.stream()
    }
}
