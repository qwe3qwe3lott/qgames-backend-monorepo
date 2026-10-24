package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.CreateRoomRequest
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.RoomRole
import site.qgames.game.api.dto.room.RoomStatus
import site.qgames.game.api.dto.room.TimeControlMode
import site.qgames.game.job.room.EmptyRoomCleanupJob
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.support.TestData.DAVE
import site.qgames.game.support.TestData.EVE
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

/**
 * Выход из комнаты — закрытие последнего потока, вход — подключение к нему. Таймеров выхода нет: вне партии ушедший
 * удаляется сразу, а игрок идущей партии закреплён до её конца и остаётся отсутствующим (leaving). Ушедший владелец
 * оставляет комнату без владельца на окно владения (15 с, application.yml): вернувшись, он снова владелец, а по
 * истечении окна RoomOwnerGraceJob отдаёт владение присутствующему участнику.
 */
class RoomLeaveScenariosTest : GameIntegrationTest() {

    @Autowired
    private lateinit var emptyRoomCleanupJob: EmptyRoomCleanupJob

    /** Готовит комнату для сценария и возвращает её id. */
    fun interface RoomSetup {
        fun RoomLeaveScenariosTest.prepare(): UUID
    }

    // --- Вне партии ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("leaversOutsideTheGame")
    fun `member outside the game is removed as soon as the stream closes`(case: String, setup: RoomSetup, leaver: UUID) {
        val roomId = with(setup) { prepare() }
        val stream = openStream(ALICE, roomId)
        val eventsBefore = events(stream).size

        leave(leaver, roomId)

        assertThat(isMember(roomId, leaver)).isFalse()
        val room = room(roomId, ALICE)
        assertThat(room.ownerId).isEqualTo(ALICE)
        assertThat(room.members.map { it.leaving }).containsOnly(false)
        assertThat(awaitEvents(stream, eventsBefore + 1).last().first).isEqualTo("users-changed")
    }

    @Test
    fun `member who comes back joins anew and may become a spectator`() {
        val roomId = roomWith(ALICE, BOB, CAROL, DAVE)
        leave(DAVE, roomId)
        join(EVE, roomId)

        val room = join(DAVE, roomId)

        assertThat(room.members.map { it.userId to it.role }).containsExactly(
            ALICE to RoomRole.PLAYER, BOB to RoomRole.PLAYER, CAROL to RoomRole.PLAYER, EVE to RoomRole.PLAYER, DAVE to RoomRole.SPECTATOR,
        )
    }

    @Test
    fun `leaving owner is removed at once and nobody owns the room during the grace`() {
        val roomId = roomWith(ALICE, BOB)
        val before = Instant.now()

        leave(ALICE, roomId)

        assertThat(isMember(roomId, ALICE)).isFalse()
        val (graceUser, deadline) = ownerGrace(roomId)!!
        assertThat(graceUser).isEqualTo(ALICE)
        assertThat(deadline).isBetween(before.plus(GRACE).minusMillis(1), Instant.now().plus(GRACE))
        val room = room(roomId, BOB)
        assertThat(room.ownerId).isNull()
        assertThat(room.members.map { it.userId to it.owner }).containsExactly(BOB to false)
        ownerCommands(roomId).forEach { (method, path) ->
            call(method, path, BOB).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("NOT_ROOM_OWNER") }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownerReturns")
    fun `owner who comes back within the grace owns the room again`(case: String, setup: RoomSetup, expectedRole: RoomRole) {
        val roomId = with(setup) { prepare() }
        leave(ALICE, roomId)
        join(EVE, roomId)

        val room = join(ALICE, roomId)

        assertThat(room.ownerId).isEqualTo(ALICE)
        assertThat(room.members.single { it.userId == ALICE }.role).isEqualTo(expectedRole)
        assertThat(ownerGrace(roomId)).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownershipAfterGrace")
    fun `after the grace ownership goes to a present member`(case: String, setup: RoomSetup, expectedOwner: UUID) {
        val roomId = with(setup) { prepare() }
        leave(ALICE, roomId)
        val stream = openStream(expectedOwner, roomId)
        val eventsBefore = events(stream).size
        expireOwnerGrace(roomId)

        assertThat(roomOwnerGraceJob.run()).isEqualTo(1)

        assertThat(room(roomId, expectedOwner).ownerId).isEqualTo(expectedOwner)
        assertThat(ownerGrace(roomId)).isNull()
        assertThat(awaitEvents(stream, eventsBefore + 1).last().first).isEqualTo("users-changed")
        assertThat(roomOwnerGraceJob.run()).isZero()
    }

    @Test
    fun `job ends only expired graces`() {
        val expired = roomWith(ALICE, BOB).also { leave(ALICE, it) }
        val running = roomWith(CAROL, DAVE).also { leave(CAROL, it) }
        expireOwnerGrace(expired)

        assertThat(roomOwnerGraceJob.run()).isEqualTo(1)

        assertThat(room(expired, BOB).ownerId).isEqualTo(BOB)
        assertThat(room(running, DAVE).ownerId).isNull()
        assertThat(ownerGrace(running)?.first).isEqualTo(CAROL)
    }

    @Test
    fun `in a room emptied during the grace the first visitor after the grace becomes the owner`() {
        val roomId = roomWith(ALICE, BOB)
        leave(BOB, roomId)
        leave(ALICE, roomId)
        assertThat(emptySince(roomId)).isNotNull()

        val duringGrace = join(DAVE, roomId)
        assertThat(duringGrace.ownerId).isNull()
        leave(DAVE, roomId)

        expireOwnerGrace(roomId)
        assertThat(roomOwnerGraceJob.run()).isEqualTo(1)
        assertThat(room(roomId, EVE).ownerId).isNull()

        val room = join(EVE, roomId)
        assertThat(room.ownerId).isEqualTo(EVE)
        assertThat(emptySince(roomId)).isNull()
    }

    @Test
    fun `newcomer does not become the owner during the grace`() {
        val roomId = roomWith(ALICE, BOB)
        leave(ALICE, roomId)

        val room = join(CAROL, roomId)

        assertThat(room.ownerId).isNull()
        assertThat(room.members.map { it.owner }).containsOnly(false)
    }

    /** Окно истекло, а джоб ещё не сработал: вход сам закрывает окно. */
    @Test
    fun `expired grace is ended by the next join`() {
        val roomId = roomWith(ALICE, BOB)
        leave(ALICE, roomId)
        expireOwnerGrace(roomId)

        val room = join(CAROL, roomId)

        assertThat(room.ownerId).isEqualTo(BOB)
        assertThat(ownerGrace(roomId)).isNull()
    }

    // --- Создатель комнаты ---

    @Test
    fun `creator becomes the owner when connecting`() {
        val created = create(ALICE)
        assertThat(created.members).isEmpty()
        assertThat(created.ownerId).isNull()
        assertThat(ownerGrace(created.id)?.first).isEqualTo(ALICE)

        val room = join(ALICE, created.id)

        assertThat(room.ownerId).isEqualTo(ALICE)
        assertThat(room.members.map { it.userId to it.role }).containsExactly(ALICE to RoomRole.PLAYER)
        assertThat(emptySince(created.id)).isNull()
        assertThat(ownerGrace(created.id)).isNull()
    }

    @Test
    fun `room of a creator who never connects stays empty without an owner`() {
        val roomId = create(ALICE).id
        assertThat(emptySince(roomId)).isNotNull()
        assertThat(join(BOB, roomId).ownerId).isNull()
        leave(BOB, roomId)

        expireOwnerGrace(roomId)
        roomOwnerGraceJob.run()

        val room = room(roomId, ALICE)
        assertThat(room.members).isEmpty()
        assertThat(room.ownerId).isNull()
        assertThat(emptySince(roomId)).isNotNull()
    }

    // --- Во время партии ---

    @ParameterizedTest(name = "paused: {0}")
    @MethodSource("paused")
    fun `player of the running game stays as an absent member and comes back to the seat`(paused: Boolean) {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(mode = TimeControlMode.PER_MOVE, moveSeconds = 30))
        if (paused) pause(ALICE, roomId)

        leave(BOB, roomId)

        assertThat(leftAt(roomId, BOB)).isNotNull()
        assertThat(room(roomId, ALICE).members.map { it.userId to it.leaving }).containsExactly(ALICE to false, BOB to true)
        roomOwnerGraceJob.run()
        emptyRoomCleanupJob.run()
        assertThat(isMember(roomId, BOB)).isTrue()

        val returned = join(BOB, roomId)
        assertThat(returned.members.map { Triple(it.userId, it.role, it.leaving) })
            .containsExactly(Triple(ALICE, RoomRole.PLAYER, false), Triple(BOB, RoomRole.PLAYER, false))
        assertThat(fullState(roomId).state.players.map { it.id }).containsExactly(ALICE, BOB)
    }

    @Test
    fun `spectator of the running game is removed at once`() {
        val roomId = roomWith(ALICE, BOB, started = true).also { join(CAROL, it) }

        leave(CAROL, roomId)

        assertThat(isMember(roomId, CAROL)).isFalse()
    }

    @Test
    fun `owner who plays leaves the game and ownership passes through the grace`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        leave(ALICE, roomId)

        assertThat(leftAt(roomId, ALICE)).isNotNull()
        assertThat(room(roomId, BOB).ownerId).isNull()
        assertThat(ownerGrace(roomId)?.first).isEqualTo(ALICE)
        expireOwnerGrace(roomId)
        roomOwnerGraceJob.run()
        val room = room(roomId, BOB)
        assertThat(room.ownerId).isEqualTo(BOB)
        assertThat(room.members.map { Triple(it.userId, it.role, it.leaving) })
            .containsExactly(Triple(ALICE, RoomRole.PLAYER, true), Triple(BOB, RoomRole.PLAYER, false))
        assertThat(fullState(roomId).state.players.map { it.id }).containsExactly(ALICE, BOB)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gameEnds")
    fun `end of the game removes absent players`(case: String, end: RoomSetupAction) {
        val roomId = roomWith(ALICE, BOB, CAROL, started = true, settings = nothingSettings(rounds = 1))
        leave(CAROL, roomId)
        val stream = openStream(ALICE, roomId)
        val eventsBefore = events(stream).size

        with(end) { run(roomId) }

        val room = room(roomId, ALICE)
        assertThat(room.status).isEqualTo(RoomStatus.WAITING)
        assertThat(room.members.map { it.userId to it.leaving }).containsExactly(ALICE to false, BOB to false)
        assertThat(awaitEvents(stream, eventsBefore + 2).drop(eventsBefore).map { it.first })
            .containsExactly("session-changed", "users-changed")
    }

    @Test
    fun `room with only absent players is deleted after the empty ttl and its game is cancelled`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        val sessionId = sessionId(roomId)
        openSessionStream(ALICE, roomId)
        leave(BOB, roomId)
        leave(ALICE, roomId)
        assertThat(emptySince(roomId)).isNotNull()
        assertThat(emptyRoomCleanupJob.run()).isZero()
        jdbcClient.sql("UPDATE \"game#room\" SET empty_since = now() - interval '31 minutes' WHERE id = :id").param("id", roomId).update()

        assertThat(emptyRoomCleanupJob.run()).isEqualTo(1)

        assertThat(countRows("\"game#room\" WHERE id = :id", "id" to roomId)).isZero()
        assertThat(countRows("\"game#room_session\"")).isZero()
        // Журнал удалён вместе с комнатой: диспетчер не найдёт объявленных событий отмены и закроет поток партии.
        awaitUntil("session stream is closed") { sessionStreamRegistry.subscribers(sessionId).isEmpty() }
    }

    /** Действие над комнатой для сценария. */
    fun interface RoomSetupAction {
        fun RoomLeaveScenariosTest.run(roomId: UUID)
    }

    private fun create(owner: UUID): RoomResponse =
        createRoomRequest(owner, CreateRoomRequest(nothingSettings())).andExpect { status { isOk() } }.body()

    private fun emptySince(roomId: UUID): Instant? =
        jdbcClient.sql("SELECT empty_since FROM \"game#room\" WHERE id = :id")
            .param("id", roomId)
            .query { rs, _ -> rs.getTimestamp("empty_since")?.toInstant() }
            .list()
            .single()

    private fun ownerCommands(roomId: UUID) = listOf(
        HttpMethod.POST to "/api/rooms/$roomId/session",
        HttpMethod.DELETE to "/api/rooms/$roomId/session",
    )

    companion object {
        private val GRACE = Duration.ofSeconds(15)

        @JvmStatic
        fun leaversOutsideTheGame(): Stream<Arguments> = Stream.of(
            arguments("player while waiting", RoomSetup { roomWith(ALICE, BOB) }, BOB),
            arguments("spectator while waiting", RoomSetup { roomWith(ALICE, BOB).also { changeRole(BOB, it, RoomRole.SPECTATOR) } }, BOB),
            arguments("spectator of the running game", RoomSetup { roomWith(ALICE, BOB, started = true).also { join(CAROL, it) } }, CAROL),
        )

        @JvmStatic
        fun ownerReturns(): Stream<Arguments> = Stream.of(
            arguments("free seat: player", RoomSetup { roomWith(ALICE, BOB) }, RoomRole.PLAYER),
            arguments("seats taken: spectator", RoomSetup { roomWith(ALICE, BOB, CAROL, DAVE) }, RoomRole.SPECTATOR),
            arguments("player of the running game: back to the seat", RoomSetup { roomWith(ALICE, BOB, started = true) }, RoomRole.PLAYER),
        )

        /** Владелец ALICE уйдёт; ожидаемый владелец после окна. */
        @JvmStatic
        fun ownershipAfterGrace(): Stream<Arguments> = Stream.of(
            arguments("the earliest player", RoomSetup { roomWith(ALICE, BOB, CAROL) }, BOB),
            arguments(
                "a later player over an earlier spectator",
                RoomSetup { roomWith(ALICE, BOB, CAROL).also { changeRole(BOB, it, RoomRole.SPECTATOR) } },
                CAROL,
            ),
            arguments(
                "a spectator when no players remain",
                RoomSetup { roomWith(ALICE, BOB).also { changeRole(BOB, it, RoomRole.SPECTATOR) } },
                BOB,
            ),
            arguments(
                "a present spectator over an absent player of the game",
                RoomSetup { roomWith(ALICE, BOB, started = true).also { join(CAROL, it); leave(BOB, it) } },
                CAROL,
            ),
        )

        @JvmStatic
        fun paused(): Stream<Boolean> = Stream.of(false, true)

        @JvmStatic
        fun gameEnds(): Stream<Arguments> = Stream.of(
            arguments("cancel", RoomSetupAction { cancel(ALICE, it) }),
            arguments("finish", RoomSetupAction { pass(ALICE, it); pass(BOB, it); pass(CAROL, it) }),
        )
    }
}
