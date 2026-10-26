package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.CreateRoomRequest
import site.qgames.game.api.dto.room.RoomResponse
import site.qgames.game.api.dto.room.RoomRole
import site.qgames.game.api.dto.room.RoomStatus
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import site.qgames.game.table.room.RoomTable
import site.qgames.game.table.session.RoomSessionTable

/**
 * Выход из комнаты — закрытие последнего потока, вход — подключение к нему. Таймеров выхода нет: вне партии ушедший
 * удаляется сразу, а игрок идущей партии закреплён до её конца и остаётся отсутствующим (leaving). Ушёл владелец —
 * владение сразу получает случайный присутствующий участник (выбор кандидатов — RoomMembershipScenariosTest).
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
    fun `leaving owner passes ownership at once`() {
        val roomId = roomWith(ALICE, BOB)
        val stream = openStream(BOB, roomId)
        val eventsBefore = events(stream).size

        leave(ALICE, roomId)

        assertThat(isMember(roomId, ALICE)).isFalse()
        val room = room(roomId, BOB)
        assertThat(room.ownerId).isEqualTo(BOB)
        assertThat(room.members.map { it.userId to it.owner }).containsExactly(BOB to true)
        assertThat(awaitEvents(stream, eventsBefore + 1).last().first).isEqualTo("users-changed")
    }

    @Test
    fun `former owner who comes back is an ordinary member`() {
        val roomId = roomWith(ALICE, BOB)
        leave(ALICE, roomId)

        val room = join(ALICE, roomId)

        assertThat(room.ownerId).isEqualTo(BOB)
        assertThat(room.members.single { it.userId == ALICE }.owner).isFalse()
    }

    // --- Создатель комнаты ---

    @Test
    fun `creator becomes the owner when connecting`() {
        val created = create(ALICE)
        assertThat(created.members).isEmpty()
        assertThat(created.ownerId).isNull()

        val room = join(ALICE, created.id)

        assertThat(room.ownerId).isEqualTo(ALICE)
        assertThat(room.members.map { it.userId to it.role }).containsExactly(ALICE to RoomRole.PLAYER)
        assertThat(emptySince(created.id)).isNull()
    }

    @Test
    fun `whoever connects first to a new room becomes the owner`() {
        val roomId = create(ALICE).id

        assertThat(join(BOB, roomId).ownerId).isEqualTo(BOB)
        assertThat(join(ALICE, roomId).ownerId).isEqualTo(BOB)
    }

    @Test
    fun `room of a creator who never connects stays empty without an owner`() {
        val roomId = create(ALICE).id

        val room = room(roomId, ALICE)
        assertThat(room.members).isEmpty()
        assertThat(room.ownerId).isNull()
        assertThat(emptySince(roomId)).isNotNull()
    }

    // --- Во время партии ---

    @ParameterizedTest(name = "paused: {0}")
    @MethodSource("paused")
    fun `player of the running game stays as an absent member and comes back to the seat`(paused: Boolean) {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(clockSeconds = 30))
        if (paused) pause(ALICE, roomId)

        leave(BOB, roomId)

        assertThat(leftAt(roomId, BOB)).isNotNull()
        assertThat(room(roomId, ALICE).members.map { it.userId to it.leaving }).containsExactly(ALICE to false, BOB to true)
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
    fun `owner who plays leaves the game and ownership passes to a present member`() {
        val roomId = roomWith(ALICE, BOB, started = true)

        leave(ALICE, roomId)

        assertThat(leftAt(roomId, ALICE)).isNotNull()
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
        db { RoomTable.update({ RoomTable.id eq roomId }) { it[emptySince] = Instant.now().minus(Duration.ofMinutes(31)) } }

        assertThat(emptyRoomCleanupJob.run()).isEqualTo(1)

        assertThat(count(RoomTable) { RoomTable.id eq roomId }).isZero()
        assertThat(count(RoomSessionTable)).isZero()
        // Журнал удалён вместе с комнатой: диспетчер не найдёт объявленных событий отмены и закроет поток партии.
        awaitUntil("session stream is closed") { sessionStreamRegistry.subscribers(sessionId).isEmpty() }
    }

    /** Действие над комнатой для сценария. */
    fun interface RoomSetupAction {
        fun RoomLeaveScenariosTest.run(roomId: UUID)
    }

    private fun create(owner: UUID): RoomResponse =
        createRoomRequest(owner, CreateRoomRequest(nothingSettings())).andExpect { status { isOk() } }.body()

    private fun emptySince(roomId: UUID): Instant? = db { RoomTable.selectAll().where { RoomTable.id eq roomId }.single()[RoomTable.emptySince] }

    companion object {
        @JvmStatic
        fun leaversOutsideTheGame(): Stream<Arguments> = Stream.of(
            arguments("player while waiting", RoomSetup { roomWith(ALICE, BOB) }, BOB),
            arguments("spectator while waiting", RoomSetup { roomWith(ALICE, BOB).also { changeRole(BOB, it, RoomRole.SPECTATOR) } }, BOB),
            arguments("spectator of the running game", RoomSetup { roomWith(ALICE, BOB, started = true).also { join(CAROL, it) } }, CAROL),
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
