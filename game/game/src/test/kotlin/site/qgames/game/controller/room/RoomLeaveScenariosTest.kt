package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.room.RoomRole
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
 * Отложенный выход: leave ставит срок (room_member.leave_deadline) по роли участника, вход в пределах срока отменяет
 * выход, а по истечении срока RoomLeaveJob удаляет участника по правилам выхода. Сроки: игроку идущей партии —
 * из правил игры («ничто» — 30 с), владельцу вне игры — 5 с, остальным — 1 с (application.yml).
 */
class RoomLeaveScenariosTest : BaseIntegrationTest() {

    /** Готовит комнату для сценария и возвращает её id. */
    fun interface RoomSetup {
        fun RoomLeaveScenariosTest.prepare(): UUID
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("graces")
    fun `leave sets a deadline by the role of the member`(case: String, setup: RoomSetup, leaver: UUID, grace: Duration) {
        val roomId = with(setup) { prepare() }
        val owner = room(roomId, ALICE).ownerId

        val before = Instant.now()
        leave(leaver, roomId)
        val after = Instant.now()

        assertThat(leaveDeadline(roomId, leaver)).isBetween(before.plus(grace).minusMillis(1), after.plus(grace))
        val room = room(roomId, ALICE)
        val member = room.members.single { it.userId == leaver }
        assertThat(member.leaving).isTrue()
        assertThat(room.ownerId).isEqualTo(owner)
        assertThat(room.members.filter { it.userId != leaver }.map { it.leaving }).containsOnly(false)
    }

    @Test
    fun `player of a paused game gets the full grace counted from the pause`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        pause(ALICE, roomId)
        val pausedAt = pausedAt(roomId)

        leave(BOB, roomId)

        assertThat(leaveDeadline(roomId, BOB)).isEqualTo(pausedAt.plusSeconds(30))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("returns")
    fun `join within the deadline cancels the leave and keeps role and ownership`(case: String, setup: RoomSetup, user: UUID) {
        val roomId = with(setup) { prepare() }
        val before = room(roomId, user)
        leave(user, roomId)

        val returned = join(user, roomId)

        assertThat(leaveDeadline(roomId, user)).isNull()
        val member = returned.members.single { it.userId == user }
        assertThat(member.leaving).isFalse()
        assertThat(member.role).isEqualTo(before.members.single { it.userId == user }.role)
        assertThat(returned.ownerId).isEqualTo(before.ownerId)
        assertThat(returned.members.map { it.userId }).isEqualTo(before.members.map { it.userId })
    }

    @Test
    fun `repeated leave does not extend the deadline`() {
        val roomId = roomWith(ALICE, BOB)
        leave(BOB, roomId)
        val first = leaveDeadline(roomId, BOB)

        leave(BOB, roomId)

        assertThat(leaveDeadline(roomId, BOB)).isEqualTo(first)
    }

    @Test
    fun `leaving member keeps the seat and the room is not empty`() {
        val roomId = roomWith(ALICE, BOB, CAROL, DAVE)
        leave(DAVE, roomId)
        leave(ALICE, roomId)

        val room = join(EVE, roomId)

        assertThat(room.members.single { it.userId == EVE }.role).isEqualTo(RoomRole.SPECTATOR)
        assertThat(countRows("room WHERE id = :id AND empty_since IS NULL", "id" to roomId)).isEqualTo(1)
    }

    @Test
    fun `job removes only members whose deadline expired`() {
        val roomId = roomWith(ALICE, BOB, CAROL)
        leave(BOB, roomId)
        leave(CAROL, roomId)
        expireLeave(roomId, BOB)

        assertThat(roomLeaveJob.run()).isEqualTo(1)

        assertThat(room(roomId, ALICE).members.map { it.userId to it.leaving }).containsExactly(ALICE to false, CAROL to true)
        assertThat(roomLeaveJob.run()).isZero()
    }

    @Test
    fun `job transfers ownership after the owner's deadline`() {
        val roomId = roomWith(ALICE, BOB)
        leave(ALICE, roomId)
        expireLeave(roomId, ALICE)

        assertThat(roomLeaveJob.run()).isEqualTo(1)

        val room = room(roomId, BOB)
        assertThat(room.ownerId).isEqualTo(BOB)
        assertThat(room.members.map { it.userId }).containsExactly(BOB)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownershipWithLeavers")
    fun `ownership goes to present members before leaving ones`(case: String, setup: RoomSetup, expectedOwner: UUID) {
        val roomId = with(setup) { prepare() }
        expireLeave(roomId, ALICE)

        assertThat(roomLeaveJob.run()).isEqualTo(1)

        val room = room(roomId, expectedOwner)
        assertThat(room.ownerId).isEqualTo(expectedOwner)
        assertThat(room.members.map { it.userId }).doesNotContain(ALICE)
    }

    @Test
    fun `job marks the room empty when the last member is removed`() {
        val roomId = roomWith(ALICE, BOB)
        leave(ALICE, roomId)
        leave(BOB, roomId)
        expireLeave(roomId, ALICE)
        expireLeave(roomId, BOB)

        assertThat(roomLeaveJob.run()).isEqualTo(2)

        assertThat(countRows("room_member WHERE room_id = :id", "id" to roomId)).isZero()
        assertThat(countRows("room WHERE id = :id AND owner_id IS NULL AND empty_since IS NOT NULL", "id" to roomId)).isEqualTo(1)
    }

    @Test
    fun `removed player of the running game keeps the seat`() {
        val roomId = roomWith(ALICE, BOB, started = true)
        leave(BOB, roomId)
        expireLeave(roomId, BOB)

        roomLeaveJob.run()

        val room = room(roomId, ALICE)
        assertThat(room.members.map { it.userId }).containsExactly(ALICE)
        assertThat(room.session!!.players).containsExactly(ALICE, BOB)
        assertThat(join(BOB, roomId).members.single { it.userId == BOB }.role).isEqualTo(RoomRole.PLAYER)
    }

    private fun pausedAt(roomId: UUID): Instant =
        jdbcClient.sql("SELECT paused_at FROM room_session WHERE room_id = :id AND status = 'IN_PROGRESS'")
            .param("id", roomId)
            .query { rs, _ -> rs.getTimestamp("paused_at").toInstant() }
            .single()

    companion object {
        private val GAME = Duration.ofSeconds(30)
        private val OWNER = Duration.ofSeconds(5)
        private val MEMBER = Duration.ofSeconds(1)

        @JvmStatic
        fun graces(): Stream<Arguments> = Stream.of(
            arguments("player of the running game", RoomSetup { roomWith(ALICE, BOB, started = true) }, BOB, GAME),
            arguments("owner who plays the running game", RoomSetup { roomWith(ALICE, BOB, started = true) }, ALICE, GAME),
            arguments("owner while waiting", RoomSetup { roomWith(ALICE, BOB) }, ALICE, OWNER),
            arguments(
                "owner who watches the running game",
                RoomSetup { roomWith(ALICE, BOB).also { changeRole(ALICE, it, RoomRole.SPECTATOR); start(ALICE, it) } },
                ALICE, OWNER,
            ),
            arguments("player while waiting", RoomSetup { roomWith(ALICE, BOB) }, BOB, MEMBER),
            arguments("spectator of the running game", RoomSetup { roomWith(ALICE, started = true).also { join(CAROL, it) } }, CAROL, MEMBER),
        )

        /** Владелец ALICE ушёл; комната готова к истечению её срока. */
        @JvmStatic
        fun ownershipWithLeavers(): Stream<Arguments> = Stream.of(
            arguments(
                "present later player over an earlier leaving player",
                RoomSetup { roomWith(ALICE, BOB, CAROL).also { leave(BOB, it); leave(ALICE, it) } },
                CAROL,
            ),
            arguments(
                "present spectator over a leaving player",
                RoomSetup {
                    roomWith(ALICE, BOB, CAROL).also {
                        changeRole(CAROL, it, RoomRole.SPECTATOR)
                        leave(BOB, it)
                        leave(ALICE, it)
                    }
                },
                CAROL,
            ),
            arguments(
                "present player over a present earlier spectator",
                RoomSetup { roomWith(ALICE, BOB, CAROL).also { changeRole(BOB, it, RoomRole.SPECTATOR); leave(ALICE, it) } },
                CAROL,
            ),
            arguments(
                "only leavers remain: the earliest leaving player",
                RoomSetup {
                    roomWith(ALICE, BOB, CAROL).also {
                        changeRole(BOB, it, RoomRole.SPECTATOR)
                        leave(BOB, it)
                        leave(CAROL, it)
                        leave(ALICE, it)
                    }
                },
                CAROL,
            ),
        )

        @JvmStatic
        fun returns(): Stream<Arguments> = Stream.of(
            arguments("owner while waiting", RoomSetup { roomWith(ALICE, BOB) }, ALICE),
            arguments(
                "spectator owner",
                RoomSetup { roomWith(ALICE, BOB).also { changeRole(ALICE, it, RoomRole.SPECTATOR) } },
                ALICE,
            ),
            arguments("player of the running game", RoomSetup { roomWith(ALICE, BOB, started = true) }, BOB),
            arguments("spectator of the running game", RoomSetup { roomWith(ALICE, started = true).also { join(CAROL, it) } }, CAROL),
        )
    }
}
