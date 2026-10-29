package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.and
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
import site.qgames.game.api.dto.room.RoomRole
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.table.room.RoomConnectionTable
import site.qgames.game.table.room.RoomTable
import java.util.UUID
import java.util.stream.Stream

/**
 * Владелец исключает участника в любой момент, в том числе игрока идущей партии. Это не бан: исключённый входит снова.
 * Подключения исключённого удаляются, его потоки комнаты и партий закрываются; дальше — как выход: игрок партии
 * остаётся отсутствующим до её конца, остальные удаляются.
 */
class RoomKickScenariosTest : GameIntegrationTest() {

    /** Готовит комнату для сценария и возвращает её id. */
    fun interface RoomSetup {
        fun RoomKickScenariosTest.prepare(): UUID
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kicksOutsideTheGame")
    fun `kicked member outside the game is removed and their streams are closed`(case: String, setup: RoomSetup) {
        val roomId = with(setup) { prepare() }
        val ownerStream = openStream(ALICE, roomId)
        val kickedStream = openStream(BOB, roomId)
        val ownerEventsBefore = events(ownerStream).size
        val kickedEventsBefore = events(kickedStream).size

        kick(ALICE, roomId, BOB).andExpect { status { isNoContent() } }

        assertThat(isMember(roomId, BOB)).isFalse()
        assertThat(connections(roomId, BOB)).isZero()
        assertThat(awaitCodes(ownerStream, ownerEventsBefore + 2, ownerEventsBefore)).containsExactly("member-kicked", "member-left")
        assertThat(awaitCodes(kickedStream, kickedEventsBefore + 1, kickedEventsBefore)).containsExactly("member-kicked")
        awaitUntil("streams of the kicked are closed") { streams(BOB, roomId).isEmpty() }
        assertThat(streams(ALICE, roomId)).isNotEmpty()
        assertThat(room(roomId, ALICE).members.map { it.id }).doesNotContain(BOB)
    }

    @Test
    fun `kicked spectator loses the game stream`() {
        val roomId = roomWith(ALICE, BOB, started = true).also { join(CAROL, it) }
        openSessionStream(CAROL, roomId)
        openSessionStream(ALICE, roomId)

        kick(ALICE, roomId, CAROL).andExpect { status { isNoContent() } }

        assertThat(isMember(roomId, CAROL)).isFalse()
        awaitUntil("game stream of the kicked is closed") { sessionStreamRegistry.subscribers(sessionId(roomId)).none { it.userId == CAROL } }
        assertThat(sessionStreamRegistry.subscribers(sessionId(roomId)).map { it.userId }).containsExactly(ALICE)
    }

    @Test
    fun `spectator who leaves loses the game stream`() {
        val roomId = roomWith(ALICE, BOB, started = true).also { join(CAROL, it) }
        openSessionStream(CAROL, roomId)

        leave(CAROL, roomId)

        awaitUntil("game stream of the leaver is closed") { sessionStreamRegistry.subscribers(sessionId(roomId)).isEmpty() }
    }

    @Test
    fun `kicked player of the running game stays absent and comes back to the seat`() {
        val roomId = roomWith(ALICE, BOB, started = true, settings = nothingSettings(clockSeconds = 30))
        openSessionStream(BOB, roomId)
        val ownerStream = openStream(ALICE, roomId)
        val eventsBefore = events(ownerStream).size

        kick(ALICE, roomId, BOB).andExpect { status { isNoContent() } }

        assertThat(leftAt(roomId, BOB)).isNotNull()
        assertThat(connections(roomId, BOB)).isZero()
        assertThat(awaitCodes(ownerStream, eventsBefore + 2, eventsBefore)).containsExactly("member-kicked", "member-leaving")
        awaitUntil("streams of the kicked are closed") {
            streams(BOB, roomId).isEmpty() && sessionStreamRegistry.subscribers(sessionId(roomId)).isEmpty()
        }
        assertThat(fullState(roomId).state.players.map { it.id }).containsExactly(ALICE, BOB)

        val returned = join(BOB, roomId)
        assertThat(returned.members.map { Triple(it.id, it.role, it.leaving) })
            .containsExactly(Triple(ALICE, RoomRole.PLAYER, false), Triple(BOB, RoomRole.PLAYER, false))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    fun `kick is refused`(case: String, user: UUID, target: UUID, expectedStatus: HttpStatus, expectedCode: String) {
        val roomId = roomWith(ALICE, BOB)

        kick(user, roomId, target).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            jsonPath("$.code") { value(expectedCode) }
        }

        assertThat(room(roomId, ALICE).members.map { it.id }).containsExactly(ALICE, BOB)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noOps")
    fun `kicking someone who is not present changes nothing`(case: String, setup: RoomSetup, target: UUID) {
        val roomId = with(setup) { prepare() }
        val seqBefore = eventSeq(roomId)

        kick(ALICE, roomId, target).andExpect { status { isNoContent() } }

        assertThat(eventSeq(roomId)).isEqualTo(seqBefore)
    }

    private fun kick(user: UUID, roomId: UUID, target: UUID) = call(HttpMethod.DELETE, "/api/rooms/$roomId/members/$target", user)

    private fun eventSeq(roomId: UUID) = db { RoomTable.selectAll().where { RoomTable.id eq roomId }.single()[RoomTable.eventSeq] }

    private fun connections(roomId: UUID, user: UUID) =
        count(RoomConnectionTable) { (RoomConnectionTable.roomId eq roomId) and (RoomConnectionTable.userId eq user) }

    companion object {

        @JvmStatic
        fun kicksOutsideTheGame(): Stream<Arguments> = Stream.of(
            arguments("player while waiting", RoomSetup { roomWith(ALICE, BOB) }),
            arguments("spectator while waiting", RoomSetup { roomWith(ALICE, BOB).also { changeRole(BOB, it, RoomRole.SPECTATOR) } }),
            arguments("spectator during the game", RoomSetup { roomWith(ALICE, CAROL, started = true).also { join(BOB, it) } }),
        )

        @JvmStatic
        fun refusals(): Stream<Arguments> = Stream.of(
            arguments("not the owner", BOB, ALICE, HttpStatus.FORBIDDEN, "NOT_ROOM_OWNER"),
            arguments("not in the room", CAROL, BOB, HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER"),
            arguments("the owner themselves", ALICE, ALICE, HttpStatus.CONFLICT, "CANNOT_KICK_SELF"),
        )

        @JvmStatic
        fun noOps(): Stream<Arguments> = Stream.of(
            arguments("not a member", RoomSetup { roomWith(ALICE) }, CAROL),
            arguments("absent player of the game", RoomSetup { roomWith(ALICE, BOB, started = true).also { leave(BOB, it) } }, BOB),
        )
    }
}
