package site.qgames.game.controller.room

import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.room.CreateRoomRequest
import site.qgames.game.gatewayapi.dto.game.GameCode
import site.qgames.game.gatewayapi.dto.room.NothingSettingsDto
import site.qgames.game.gatewayapi.dto.room.RoomResponse
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.game.gatewayapi.dto.room.TimeControlDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.EVE
import site.qgames.games.api.TimeControl
import site.qgames.games.api.TimeControlRules
import java.util.UUID
import java.util.stream.Stream
import site.qgames.games.api.TimeControlMode as GameTimeControlMode

/**
 * Настройки комнаты: задаются при создании (POST /api/games/{gameCode}/rooms) и меняются владельцем между партиями
 * (PUT …/settings). Границы — из правил «игры в ничто»: 1–50 кругов, время на ход 5–600 с.
 */
class RoomSettingsScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("validSettings")
    fun `room is created with the given settings`(case: String, settings: RoomSettingsDto) {
        val room = createRoom(ALICE, settings)

        assertThat(room.settings).isEqualTo(settings)
        assertThat(storedSettings(room.id)).isEqualTo(
            StoredSettings(settings.timeControl.mode.name, settings.timeControl.moveSeconds, rounds(settings)),
        )
        assertThat(room.ownerId).isEqualTo(ALICE)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSettings")
    fun `room with settings out of the game limits is not created`(case: String, settings: RoomSettingsDto, field: String) {
        createRoomRequest(ALICE, CreateRoomRequest(settings)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors['settings.$field']") { exists() }
        }
        assertThat(countRows("room")).isZero()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedCreateBodies")
    fun `malformed create request is rejected`(case: String, body: Any, field: String?) {
        createRoomRequest(ALICE, body).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            if (field != null) jsonPath("$.errors['$field']") { exists() }
        }
        assertThat(countRows("room")).isZero()
    }

    @Test
    fun `settings change with a foreign game settings discriminator is rejected by the field`() {
        val roomId = roomWith(ALICE)
        val body = mapOf("timeControl" to mapOf("mode" to "NONE"), "gameSettings" to mapOf("gameCode" to "chess", "rounds" to 5))

        updateSettingsRequest(ALICE, roomId, body).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors['gameSettings.gameCode']") { value("unsupported value") }
        }
        assertThat(storedSettings(roomId).rounds).isEqualTo(3)
    }

    @Test
    fun `room of an unknown game is not created`() {
        createRoomRequest(ALICE, CreateRoomRequest(nothingSettings()), gameCode = "chess").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
        assertThat(countRows("room")).isZero()
    }

    @Test
    fun `time control mode the game does not support is rejected`() {
        every { nothingGameRules.timeControl } returns
            TimeControlRules(setOf(GameTimeControlMode.NONE), 5..600, TimeControl(GameTimeControlMode.NONE, null))

        createRoomRequest(ALICE, CreateRoomRequest(nothingSettings(mode = TimeControlMode.PER_MOVE, moveSeconds = 30))).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors['settings.timeControl.mode']") { exists() }
        }
        assertThat(countRows("room")).isZero()
    }

    @Test
    fun `owner changes the settings between games and the next game uses them`() {
        val roomId = roomWith(ALICE, BOB)
        val settings = nothingSettings(rounds = 1, mode = TimeControlMode.PER_MOVE, moveSeconds = 45)

        val updated = updateSettingsRequest(ALICE, roomId, settings).andExpect { status { isOk() } }.body<RoomResponse>()

        assertThat(updated.settings).isEqualTo(settings)
        assertThat(storedSettings(roomId)).isEqualTo(StoredSettings("PER_MOVE", 45, 1))
        val started = start(ALICE, roomId)
        assertThat(started.session!!.state).containsEntry("rounds", 1)
        val snapshot = jdbcClient.sql(
            "SELECT time_control_mode, move_seconds, game_settings->>'rounds' AS rounds FROM room_session WHERE room_id = :id",
        ).param("id", roomId).query().singleRow()
        assertThat(snapshot).containsEntry("time_control_mode", "PER_MOVE").containsEntry("move_seconds", 45).containsEntry("rounds", "1")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedUpdates")
    fun `settings change is rejected`(
        case: String,
        setup: RoomSetup,
        user: UUID,
        gameCode: String,
        settings: RoomSettingsDto,
        expectedStatus: HttpStatus,
        expectedCode: String,
        expectedField: String?,
    ) {
        val roomId = with(setup) { prepare() }
        val before = storedSettings(roomId)

        updateSettingsRequest(user, roomId, settings, gameCode).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            jsonPath("$.code") { value(expectedCode) }
            if (expectedField != null) jsonPath("$.errors['$expectedField']") { exists() }
        }
        assertThat(storedSettings(roomId)).isEqualTo(before)
    }

    /** Готовит комнату для сценария и возвращает её id. */
    fun interface RoomSetup {
        fun RoomSettingsScenariosTest.prepare(): UUID
    }

    private data class StoredSettings(val mode: String, val moveSeconds: Int?, val rounds: Int)

    private fun storedSettings(roomId: UUID): StoredSettings =
        jdbcClient.sql("SELECT time_control_mode, move_seconds, (game_settings->>'rounds')::int AS rounds FROM room WHERE id = :id")
            .param("id", roomId)
            .query { rs, _ ->
                StoredSettings(rs.getString("time_control_mode"), rs.getObject("move_seconds") as Int?, rs.getInt("rounds"))
            }
            .single()

    private fun rounds(settings: RoomSettingsDto) =
        (settings.gameSettings as NothingSettingsDto).rounds

    companion object {
        private fun settings(rounds: Int = 3, mode: TimeControlMode = TimeControlMode.NONE, moveSeconds: Int? = null) =
            RoomSettingsDto(
                TimeControlDto(mode, moveSeconds),
                NothingSettingsDto(GameCode.NOTHING, rounds),
            )

        private fun gameSettingsJson(rounds: Int = 3) = mapOf("gameCode" to "nothing", "rounds" to rounds)

        @JvmStatic
        fun validSettings(): Stream<Arguments> = Stream.of(
            arguments("minimum rounds, no timer", settings(rounds = 1)),
            arguments("maximum rounds", settings(rounds = 50)),
            arguments("shortest move time", settings(mode = TimeControlMode.PER_MOVE, moveSeconds = 5)),
            arguments("longest move time", settings(rounds = 7, mode = TimeControlMode.PER_MOVE, moveSeconds = 600)),
        )

        @JvmStatic
        fun invalidSettings(): Stream<Arguments> = Stream.of(
            arguments("zero rounds", settings(rounds = 0), "gameSettings.rounds"),
            arguments("too many rounds", settings(rounds = 51), "gameSettings.rounds"),
            arguments("move time too short", settings(mode = TimeControlMode.PER_MOVE, moveSeconds = 4), "timeControl.moveSeconds"),
            arguments("move time too long", settings(mode = TimeControlMode.PER_MOVE, moveSeconds = 601), "timeControl.moveSeconds"),
            arguments("timer without move time", settings(mode = TimeControlMode.PER_MOVE, moveSeconds = null), "timeControl.moveSeconds"),
            arguments("move time without a timer", settings(mode = TimeControlMode.NONE, moveSeconds = 30), "timeControl.moveSeconds"),
        )

        @JvmStatic
        fun malformedCreateBodies(): Stream<Arguments> = Stream.of(
            arguments("no settings", emptyMap<String, Any?>(), null),
            arguments("body with the game code only", mapOf("gameCode" to "nothing"), null),
            arguments(
                "foreign game settings discriminator",
                mapOf("settings" to mapOf("timeControl" to mapOf("mode" to "NONE"), "gameSettings" to mapOf("gameCode" to "chess", "rounds" to 3))),
                "settings.gameSettings.gameCode",
            ),
            arguments(
                "game settings without discriminator",
                mapOf("settings" to mapOf("timeControl" to mapOf("mode" to "NONE"), "gameSettings" to mapOf("rounds" to 3))),
                "settings.gameSettings.gameCode",
            ),
            arguments(
                "unknown time control mode",
                mapOf("settings" to mapOf("timeControl" to mapOf("mode" to "CHESS"), "gameSettings" to gameSettingsJson())),
                null,
            ),
            arguments("no time control", mapOf("settings" to mapOf("gameSettings" to gameSettingsJson())), null),
        )

        @JvmStatic
        fun rejectedUpdates(): Stream<Arguments> = Stream.of(
            arguments(
                "not the owner", RoomSetup { roomWith(ALICE, BOB) }, BOB, "nothing", settings(rounds = 5),
                HttpStatus.FORBIDDEN, "NOT_ROOM_OWNER", null,
            ),
            arguments(
                "not in the room", RoomSetup { roomWith(ALICE) }, EVE, "nothing", settings(rounds = 5),
                HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER", null,
            ),
            arguments(
                "game in progress", RoomSetup { roomWith(ALICE, BOB, started = true) }, ALICE, "nothing", settings(rounds = 5),
                HttpStatus.CONFLICT, "ROOM_NOT_WAITING", null,
            ),
            arguments(
                "unknown game code", RoomSetup { roomWith(ALICE) }, ALICE, "chess", settings(rounds = 5),
                HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", null,
            ),
            arguments(
                "rounds out of limits", RoomSetup { roomWith(ALICE) }, ALICE, "nothing", settings(rounds = 51),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "gameSettings.rounds",
            ),
            arguments(
                "owner-spectator sends a timer without move time",
                RoomSetup { roomWith(ALICE, BOB).also { changeRole(ALICE, it, RoomRole.SPECTATOR) } },
                ALICE, "nothing", settings(mode = TimeControlMode.PER_MOVE),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "timeControl.moveSeconds",
            ),
        )
    }
}
