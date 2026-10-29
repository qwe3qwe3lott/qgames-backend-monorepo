package site.qgames.game.controller.room

import io.mockk.every
import java.util.UUID
import java.util.stream.Stream
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.room.CheckersSimpleSettingsDto
import site.qgames.game.api.dto.room.CheckersTime
import site.qgames.game.api.dto.room.CheckersVariant
import site.qgames.game.api.dto.room.NothingAdvancedSettingsDto
import site.qgames.game.api.dto.room.NothingSimpleSettingsDto
import site.qgames.game.api.dto.room.NothingTime
import site.qgames.game.api.dto.room.RoomOptionsDto
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.RoomRole
import site.qgames.game.api.dto.room.RoomSettingsDto
import site.qgames.game.api.dto.room.RoomStatus
import site.qgames.game.api.dto.room.TimeBonusType
import site.qgames.game.api.dto.room.TimeControlDto
import site.qgames.game.api.dto.room.UpdateRoomSettingsRequest
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.EVE
import site.qgames.game.support.TestData.NOTHING_DEFAULT_SETTINGS
import site.qgames.game.support.TestData.checkersAdvanced
import site.qgames.game.table.room.RoomTable
import site.qgames.games.core.TimeControlRules

/**
 * Настройки комнаты: комната создаётся с настройками игры по умолчанию (POST /api/games/{gameCode}/rooms), владелец
 * меняет группы ручкой PUT …/settings — room в любой момент, simple или advanced между партиями. Перевод simple в
 * advanced — POST /api/games/{gameCode}/advanced-settings. Границы — из правил «игры в ничто»: 1–50 кругов, часы —
 * запас 5–3600 с, добавочное время 0–60 с, можно без таймера; лимит наблюдателей — 0–12.
 */
class RoomSettingsScenariosTest : GameIntegrationTest() {

    @Test
    fun `room is created with the default settings of the game in the simple mode`() {
        val created = createRoomRequest(ALICE).andExpect { status { isOk() } }.body<RoomResponse>()

        assertThat(created.settings).isEqualTo(NOTHING_DEFAULT_SETTINGS)
        assertThat(stored(created.id)).isEqualTo(tree(NOTHING_DEFAULT_SETTINGS))
    }

    @Test
    fun `room of an unknown game is not created`() {
        createRoomRequest(ALICE, gameCode = "chess").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
        assertThat(count(RoomTable)).isZero()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validAdvancedSettings")
    fun `advanced settings switch the room to the advanced mode`(case: String, advanced: NothingAdvancedSettingsDto) {
        val roomId = roomWith(ALICE)

        val updated = update(ALICE, roomId, UpdateRoomSettingsRequest(advanced = advanced))

        assertThat(updated.settings).isEqualTo(RoomSettingsDto(RoomOptionsDto(8), simple = null, advanced = advanced))
        assertThat(stored(roomId)).isEqualTo(tree(updated.settings))
    }

    @Test
    fun `simple settings switch the room back to the simple mode and give the advanced ones`() {
        val roomId = roomWith(ALICE)
        update(ALICE, roomId, UpdateRoomSettingsRequest(advanced = nothingSettings(rounds = 9, clockSeconds = 100)))
        val simple = NothingSimpleSettingsDto(GameCode.NOTHING, rounds = 4, time = NothingTime.CLASSIC)

        val updated = update(ALICE, roomId, UpdateRoomSettingsRequest(simple = simple))

        assertThat(updated.settings).isEqualTo(
            RoomSettingsDto(RoomOptionsDto(8), simple, NothingAdvancedSettingsDto(GameCode.NOTHING, 4, TimeControlDto(true, 30, TimeBonusType.INCREMENT, 5))),
        )
        assertThat(stored(roomId)).isEqualTo(tree(updated.settings))
    }

    @Test
    fun `owner changes the settings between games and the next game uses the advanced ones`() {
        val roomId = roomWith(ALICE, BOB)
        val advanced = nothingSettings(rounds = 1, clockSeconds = 45, bonusSeconds = 2)

        update(ALICE, roomId, UpdateRoomSettingsRequest(advanced = advanced))

        start(ALICE, roomId)
        assertThat(fullState(roomId).state.turns.rounds).isEqualTo(1)
        assertThat(fullState(roomId).state.timer).isNotNull()
        val session = roomSessionRepository.findLatest(roomId)!!
        assertThat(jsonMapper.readTree(session.settings)).isEqualTo(
            jsonMapper.readTree("""{"rounds": 1, "timeControl": {"enabled": true, "baseSeconds": 45, "bonusType": "INCREMENT", "bonusSeconds": 2}}"""),
        )
    }

    @Test
    fun `room settings change at any time, even during a game, and keep the game settings`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        val before = stored(roomId)

        val updated = update(ALICE, roomId, UpdateRoomSettingsRequest(room = RoomOptionsDto(0)))

        assertThat(updated.status).isEqualTo(RoomStatus.IN_GAME)
        assertThat(updated.settings).isEqualTo(NOTHING_DEFAULT_SETTINGS.copy(room = RoomOptionsDto(0)))
        assertThat(stored(roomId) - "room").isEqualTo(before - "room")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUpdates")
    fun `settings out of the limits are rejected by the field`(case: String, body: UpdateRoomSettingsRequest, field: String) {
        val roomId = roomWith(ALICE)
        val before = stored(roomId)

        updateSettingsRequest(ALICE, roomId, body).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors['$field']") { exists() }
        }
        assertThat(stored(roomId)).isEqualTo(before)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedBodies")
    fun `malformed settings request is rejected`(case: String, body: Any, field: String?) {
        val roomId = roomWith(ALICE)
        val before = stored(roomId)

        updateSettingsRequest(ALICE, roomId, body).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            if (field != null) jsonPath("$.errors['$field']") { exists() }
        }
        assertThat(stored(roomId)).isEqualTo(before)
    }

    @Test
    fun `a clock turned off when the game requires one is rejected`() {
        every { nothingGameRules.timeControl } returns TimeControlRules(optional = false, 5..600, 0..60)
        val roomId = roomWith(ALICE)

        updateSettingsRequest(ALICE, roomId, UpdateRoomSettingsRequest(advanced = nothingSettings())).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors['advanced.timeControl.enabled']") { exists() }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedUpdates")
    fun `settings change is rejected`(
        case: String,
        setup: RoomSetup,
        user: UUID,
        gameCode: String,
        body: UpdateRoomSettingsRequest,
        expectedStatus: HttpStatus,
        expectedCode: String,
    ) {
        val roomId = with(setup) { prepare() }
        val before = stored(roomId)

        updateSettingsRequest(user, roomId, body, gameCode).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            jsonPath("$.code") { value(expectedCode) }
        }
        assertThat(stored(roomId)).isEqualTo(before)
    }

    @Test
    fun `simple settings are translated into the advanced ones`() {
        val simple = CheckersSimpleSettingsDto(GameCode.CHECKERS, CheckersVariant.INTERNATIONAL, CheckersTime.BLITZ)

        call(HttpMethod.POST, "/api/games/checkers/advanced-settings", ALICE, simple).andExpect {
            status { isOk() }
            content { json(jsonMapper.writeValueAsString(checkersAdvanced(CheckersVariant.INTERNATIONAL, TimeControlDto(true, 600, TimeBonusType.INCREMENT, 5))), strict = true) }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTranslations")
    fun `translation of simple settings is rejected`(case: String, user: UUID?, gameCode: String, body: Any, status: HttpStatus, code: String, field: String?) {
        call(HttpMethod.POST, "/api/games/$gameCode/advanced-settings", user, body).andExpect {
            status { isEqualTo(status.value()) }
            jsonPath("$.code") { value(code) }
            if (field != null) jsonPath("$.errors['$field']") { exists() }
        }
    }

    /** Готовит комнату для сценария и возвращает её id. */
    fun interface RoomSetup {
        fun RoomSettingsScenariosTest.prepare(): UUID
    }

    /** Меняет настройки (ответ без тела) и возвращает комнату глазами [user]. */
    private fun update(user: UUID, roomId: UUID, body: UpdateRoomSettingsRequest): RoomResponse {
        updateSettingsRequest(user, roomId, body).andExpect { status { isNoContent() } }
        return room(roomId, user)
    }

    /** Колонка `room.settings` как дерево: `{"room": …, "simple": … | null, "advanced": …}`, без кодов игры. */
    private fun stored(roomId: UUID): Map<String, Any?> = db {
        RoomTable.selectAll().where { RoomTable.id eq roomId }.single().let {
            @Suppress("UNCHECKED_CAST")
            jsonMapper.readValue(it[RoomTable.settings], Map::class.java) as Map<String, Any?>
        }
    }

    /** Ожидаемая колонка `room.settings` для настроек ответа: настройки игры хранятся без кода игры. */
    private fun tree(settings: RoomSettingsDto): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val dto = jsonMapper.convertValue(settings, Map::class.java) as Map<String, Any?>
        fun withoutCode(group: Any?) = (group as Map<*, *>?)?.minus("gameCode")
        return mapOf("room" to dto["room"], "simple" to withoutCode(dto["simple"]), "advanced" to withoutCode(dto["advanced"]))
    }

    companion object {
        private fun advanced(
            rounds: Int = 3,
            clockSeconds: Int? = null,
            bonusSeconds: Int = 0,
            bonusType: TimeBonusType = TimeBonusType.INCREMENT,
        ) =
            NothingAdvancedSettingsDto(
                GameCode.NOTHING,
                rounds,
                clockSeconds?.let { TimeControlDto(true, it, bonusType, bonusSeconds) } ?: TimeControlDto(false, 30, TimeBonusType.INCREMENT, 5),
            )

        private fun simple(rounds: Int = 3) = NothingSimpleSettingsDto(GameCode.NOTHING, rounds, NothingTime.NONE)

        @JvmStatic
        fun validAdvancedSettings(): Stream<Arguments> = Stream.of(
            arguments("minimum rounds, no timer", advanced(rounds = 1)),
            arguments("maximum rounds", advanced(rounds = 50)),
            arguments("smallest clock without bonus", advanced(clockSeconds = 5)),
            arguments("largest clock and increment", advanced(rounds = 7, clockSeconds = 3600, bonusSeconds = 60)),
            arguments("clock with delay", advanced(clockSeconds = 30, bonusSeconds = 5, bonusType = TimeBonusType.DELAY)),
            arguments("values of a turned off clock are not checked", advanced().copy(timeControl = TimeControlDto(false, 1, TimeBonusType.INCREMENT, 999))),
        )

        @JvmStatic
        fun invalidUpdates(): Stream<Arguments> = Stream.of(
            arguments("no group", UpdateRoomSettingsRequest(), "room"),
            arguments("simple and advanced together", UpdateRoomSettingsRequest(simple = simple(), advanced = advanced()), "advanced"),
            arguments("negative spectators limit", UpdateRoomSettingsRequest(room = RoomOptionsDto(-1)), "room.maxSpectators"),
            arguments("spectators limit too large", UpdateRoomSettingsRequest(room = RoomOptionsDto(13)), "room.maxSpectators"),
            arguments("zero rounds in simple", UpdateRoomSettingsRequest(simple = simple(rounds = 0)), "simple.rounds"),
            arguments("zero rounds in advanced", UpdateRoomSettingsRequest(advanced = advanced(rounds = 0)), "advanced.rounds"),
            arguments("too many rounds", UpdateRoomSettingsRequest(advanced = advanced(rounds = 51)), "advanced.rounds"),
            arguments("clock too short", UpdateRoomSettingsRequest(advanced = advanced(clockSeconds = 4)), "advanced.timeControl.baseSeconds"),
            arguments("clock too long", UpdateRoomSettingsRequest(advanced = advanced(clockSeconds = 3601)), "advanced.timeControl.baseSeconds"),
            arguments(
                "bonus too large",
                UpdateRoomSettingsRequest(advanced = advanced(clockSeconds = 30, bonusSeconds = 61)),
                "advanced.timeControl.bonusSeconds",
            ),
            arguments(
                "settings of another game",
                UpdateRoomSettingsRequest(simple = CheckersSimpleSettingsDto(GameCode.CHECKERS, CheckersVariant.RUSSIAN, CheckersTime.NONE)),
                "simple.gameCode",
            ),
        )

        @JvmStatic
        fun malformedBodies(): Stream<Arguments> = Stream.of(
            arguments(
                "foreign game settings discriminator",
                mapOf("advanced" to mapOf("gameCode" to "chess", "rounds" to 3)),
                "advanced.gameCode",
            ),
            arguments("game settings without discriminator", mapOf("simple" to mapOf("rounds" to 3, "time" to "NONE")), "simple.gameCode"),
            arguments("simple without the time", mapOf("simple" to mapOf("gameCode" to "nothing", "rounds" to 3)), null),
            arguments(
                "clock without the base time",
                mapOf("advanced" to mapOf("gameCode" to "nothing", "rounds" to 3, "timeControl" to mapOf("enabled" to true, "bonusType" to "INCREMENT", "bonusSeconds" to 5))),
                null,
            ),
            arguments(
                "clock without the bonus type",
                mapOf("advanced" to mapOf("gameCode" to "nothing", "rounds" to 3, "timeControl" to mapOf("enabled" to true, "baseSeconds" to 30, "bonusSeconds" to 5))),
                null,
            ),
        )

        @JvmStatic
        fun rejectedUpdates(): Stream<Arguments> = Stream.of(
            arguments(
                "not the owner", RoomSetup { roomWith(ALICE, BOB) }, BOB, "nothing", UpdateRoomSettingsRequest(room = RoomOptionsDto(2)),
                HttpStatus.FORBIDDEN, "NOT_ROOM_OWNER",
            ),
            arguments(
                "not in the room", RoomSetup { roomWith(ALICE) }, EVE, "nothing", UpdateRoomSettingsRequest(room = RoomOptionsDto(2)),
                HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER",
            ),
            arguments(
                "simple during a game", RoomSetup { roomWith(ALICE, BOB, started = true) }, ALICE, "nothing",
                UpdateRoomSettingsRequest(simple = simple(rounds = 5)), HttpStatus.CONFLICT, "ROOM_NOT_WAITING",
            ),
            arguments(
                "advanced with the room settings during a game", RoomSetup { roomWith(ALICE, BOB, started = true) }, ALICE, "nothing",
                UpdateRoomSettingsRequest(room = RoomOptionsDto(2), advanced = advanced(rounds = 5)), HttpStatus.CONFLICT, "ROOM_NOT_WAITING",
            ),
            arguments(
                "unknown game code", RoomSetup { roomWith(ALICE) }, ALICE, "chess", UpdateRoomSettingsRequest(room = RoomOptionsDto(2)),
                HttpStatus.NOT_FOUND, "GAME_NOT_FOUND",
            ),
            arguments(
                "room of another game", RoomSetup { roomWith(ALICE) }, ALICE, "checkers", UpdateRoomSettingsRequest(room = RoomOptionsDto(2)),
                HttpStatus.CONFLICT, "GAME_TYPE_MISMATCH",
            ),
            arguments(
                "owner-spectator sends a clock out of limits",
                RoomSetup { roomWith(ALICE, BOB).also { changeRole(ALICE, it, RoomRole.SPECTATOR) } },
                ALICE, "nothing", UpdateRoomSettingsRequest(advanced = advanced(clockSeconds = 4)),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            ),
        )

        @JvmStatic
        fun rejectedTranslations(): Stream<Arguments> = Stream.of(
            arguments("guest", null, "nothing", simple(), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null),
            arguments("unknown game", ALICE, "chess", simple(), HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", null),
            arguments("not published game", ALICE, "carcassonne", simple(), HttpStatus.NOT_FOUND, "GAME_NOT_FOUND", null),
            arguments("rounds out of limits", ALICE, "nothing", simple(rounds = 0), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "rounds"),
            arguments("settings of another game", ALICE, "checkers", simple(), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "gameCode"),
        )
    }
}
