package site.qgames.game.controller.room

import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.CreateRoomRequest
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.room.NothingSettingsDto
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.RoomRole
import site.qgames.game.api.dto.room.RoomSettingsDto
import site.qgames.game.api.dto.room.TimeControlDto
import site.qgames.game.model.room.RoomTimeControlModel
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.EVE
import site.qgames.games.api.TimeControl
import site.qgames.games.api.TimeControlRules
import java.util.UUID
import java.util.stream.Stream
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import site.qgames.game.table.room.RoomTable

/**
 * Настройки комнаты: задаются при создании (POST /api/games/{gameCode}/rooms) и меняются владельцем между партиями
 * (PUT …/settings). Границы — из правил «игры в ничто»: 1–50 кругов, шахматные часы — запас 5–3600 с, добавка 0–60 с,
 * можно без таймера.
 */
class RoomSettingsScenariosTest : GameIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("validSettings")
    fun `room is created with the given settings`(case: String, settings: RoomSettingsDto) {
        val room = createRoom(ALICE, settings)

        assertThat(room.settings).isEqualTo(settings)
        assertThat(storedSettings(room.id)).isEqualTo(StoredSettings(settings.timeControl, rounds(settings)))
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
        assertThat(count(RoomTable)).isZero()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedCreateBodies")
    fun `malformed create request is rejected`(case: String, body: Any, field: String?) {
        createRoomRequest(ALICE, body).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            if (field != null) jsonPath("$.errors['$field']") { exists() }
        }
        assertThat(count(RoomTable)).isZero()
    }

    @Test
    fun `settings change with a foreign game settings discriminator is rejected by the field`() {
        val roomId = roomWith(ALICE)
        val body = mapOf("timeControl" to null, "gameSettings" to mapOf("gameCode" to "chess", "rounds" to 5))

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
        assertThat(count(RoomTable)).isZero()
    }

    @Test
    fun `a game without the timer when the game requires a clock is rejected`() {
        every { nothingGameRules.timeControl } returns TimeControlRules(optional = false, 5..600, 0..60, TimeControl(30, 0))

        createRoomRequest(ALICE, CreateRoomRequest(nothingSettings())).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors['settings.timeControl']") { exists() }
        }
        assertThat(count(RoomTable)).isZero()
    }

    @Test
    fun `owner changes the settings between games and the next game uses them`() {
        val roomId = roomWith(ALICE, BOB)
        val settings = nothingSettings(rounds = 1, clockSeconds = 45, incrementSeconds = 2)

        val updated = updateSettingsRequest(ALICE, roomId, settings).andExpect { status { isOk() } }.body<RoomResponse>()

        assertThat(updated.settings).isEqualTo(settings)
        assertThat(storedSettings(roomId)).isEqualTo(StoredSettings(TimeControlDto(45, 2), 1))
        val started = start(ALICE, roomId)
        assertThat(started.session).isNotNull()
        assertThat(fullState(roomId).state.turns.rounds).isEqualTo(1)
        assertThat(fullState(roomId).state.timer).isNotNull()
        val session = roomSessionRepository.findLatest(roomId)!!
        assertThat(session.timeControl).isEqualTo(RoomTimeControlModel(45, 2))
        assertThat(jsonMapper.readTree(session.gameSettings)["rounds"].asInt()).isEqualTo(1)
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

    private data class StoredSettings(val timeControl: TimeControlDto?, val rounds: Int)

    /** Настройки из колонки `game_settings`: `{"timeControl": …, "gameSettings": {…}}`. */
    private fun storedSettings(roomId: UUID): StoredSettings =
        db {
            RoomTable.selectAll().where { RoomTable.id eq roomId }.single().let {
                val column = jsonMapper.readTree(it[RoomTable.gameSettings])
                val timeControl = column["timeControl"].takeUnless { node -> node.isNull }?.let { node -> jsonMapper.treeToValue(node, TimeControlDto::class.java) }
                StoredSettings(timeControl, column["gameSettings"]["rounds"].asInt())
            }
        }

    private fun rounds(settings: RoomSettingsDto) =
        (settings.gameSettings as NothingSettingsDto).rounds

    companion object {
        private fun settings(rounds: Int = 3, clockSeconds: Int? = null, incrementSeconds: Int = 0) =
            RoomSettingsDto(
                clockSeconds?.let { TimeControlDto(it, incrementSeconds) },
                NothingSettingsDto(GameCode.NOTHING, rounds),
            )

        private fun gameSettingsJson(rounds: Int = 3) = mapOf("gameCode" to "nothing", "rounds" to rounds)

        @JvmStatic
        fun validSettings(): Stream<Arguments> = Stream.of(
            arguments("minimum rounds, no timer", settings(rounds = 1)),
            arguments("maximum rounds", settings(rounds = 50)),
            arguments("smallest clock without increment", settings(clockSeconds = 5)),
            arguments("largest clock and increment", settings(rounds = 7, clockSeconds = 3600, incrementSeconds = 60)),
        )

        @JvmStatic
        fun invalidSettings(): Stream<Arguments> = Stream.of(
            arguments("zero rounds", settings(rounds = 0), "gameSettings.rounds"),
            arguments("too many rounds", settings(rounds = 51), "gameSettings.rounds"),
            arguments("clock too short", settings(clockSeconds = 4), "timeControl.baseSeconds"),
            arguments("clock too long", settings(clockSeconds = 3601), "timeControl.baseSeconds"),
            arguments("negative increment", settings(clockSeconds = 30, incrementSeconds = -1), "timeControl.incrementSeconds"),
            arguments("increment too large", settings(clockSeconds = 30, incrementSeconds = 61), "timeControl.incrementSeconds"),
        )

        @JvmStatic
        fun malformedCreateBodies(): Stream<Arguments> = Stream.of(
            arguments("no settings", emptyMap<String, Any?>(), null),
            arguments("body with the game code only", mapOf("gameCode" to "nothing"), null),
            arguments(
                "foreign game settings discriminator",
                mapOf("settings" to mapOf("timeControl" to null, "gameSettings" to mapOf("gameCode" to "chess", "rounds" to 3))),
                "settings.gameSettings.gameCode",
            ),
            arguments(
                "game settings without discriminator",
                mapOf("settings" to mapOf("timeControl" to null, "gameSettings" to mapOf("rounds" to 3))),
                "settings.gameSettings.gameCode",
            ),
            arguments(
                "clock without the base time",
                mapOf("settings" to mapOf("timeControl" to mapOf("incrementSeconds" to 5), "gameSettings" to gameSettingsJson())),
                null,
            ),
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
                "owner-spectator sends a clock out of limits",
                RoomSetup { roomWith(ALICE, BOB).also { changeRole(ALICE, it, RoomRole.SPECTATOR) } },
                ALICE, "nothing", settings(clockSeconds = 4),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "timeControl.baseSeconds",
            ),
        )
    }
}
