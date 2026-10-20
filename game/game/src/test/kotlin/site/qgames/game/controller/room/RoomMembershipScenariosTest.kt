package site.qgames.game.controller.room

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.room.Avatar
import site.qgames.game.gatewayapi.dto.room.ChangeRoleRequest
import site.qgames.game.gatewayapi.dto.room.CreateRoomRequest
import site.qgames.game.gatewayapi.dto.room.GameType
import site.qgames.game.gatewayapi.dto.room.MoveRequest
import site.qgames.game.gatewayapi.dto.room.RoomRole
import site.qgames.game.gatewayapi.dto.room.RoomStatus
import site.qgames.game.job.room.EmptyRoomCleanupJob
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.support.TestData.DAVE
import site.qgames.game.support.TestData.EVE
import site.qgames.game.support.TestData.NOTHING_DEFAULT_SETTINGS
import java.util.UUID
import java.util.stream.Stream

class RoomMembershipScenariosTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var emptyRoomCleanupJob: EmptyRoomCleanupJob

    /** Готовит комнату для сценария и возвращает её id. */
    fun interface RoomSetup {
        fun RoomMembershipScenariosTest.prepare(): UUID
    }

    @Test
    fun `creator becomes owner and the first player, member profile comes from the replica`() {
        val room = createRoom(ALICE)

        assertThat(room.gameType).isEqualTo(GameType.NOTHING)
        assertThat(room.status).isEqualTo(RoomStatus.WAITING)
        assertThat(room.ownerId).isEqualTo(ALICE)
        assertThat(room.minPlayers to room.maxPlayers).isEqualTo(1 to 4)
        assertThat(room.session).isNull()
        assertThat(room.settings).isEqualTo(NOTHING_DEFAULT_SETTINGS)
        val member = room.members.single()
        assertThat(member.userId).isEqualTo(ALICE)
        assertThat(member.role).isEqualTo(RoomRole.PLAYER)
        assertThat(member.owner).isTrue()
        assertThat(member.nickname).isEqualTo("Alice")
        assertThat(member.avatar).isEqualTo(Avatar.CAT)
    }

    @Test
    fun `room is visible by link before joining`() {
        val roomId = createRoom(ALICE).id

        val seenByBob = room(roomId, BOB)

        assertThat(seenByBob.members.map { it.userId }).containsExactly(ALICE)
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("roomOperations")
    fun `operations on an unknown room return not found`(method: HttpMethod, path: String, body: Any?) {
        call(method, path.replace("{id}", UUID.randomUUID().toString()), ALICE, body).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("ROOM_NOT_FOUND") }
        }
    }

    @Test
    fun `request without user header is unauthenticated`() {
        createRoomRequest(null, CreateRoomRequest(nothingSettings())).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("joins")
    fun `joining by link`(case: String, setup: RoomSetup, joiner: UUID, expectedRole: RoomRole) {
        val roomId = with(setup) { prepare() }

        val room = join(joiner, roomId)

        assertThat(room.members.single { it.userId == joiner }.role).isEqualTo(expectedRole)
        assertThat(room.members.map { it.userId }).doesNotHaveDuplicates()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownerChanges")
    fun `ownership after somebody leaves`(case: String, setup: RoomSetup, leaver: UUID, expectedOwner: UUID?) {
        val roomId = with(setup) { prepare() }

        leaveCompletely(leaver, roomId)

        val viewer = expectedOwner ?: ALICE
        val room = room(roomId, viewer)
        assertThat(room.ownerId).isEqualTo(expectedOwner)
        assertThat(room.members.map { it.userId }).doesNotContain(leaver)
    }

    @Test
    fun `empty room waits for its first visitor who becomes the owner`() {
        val roomId = roomWith(ALICE, BOB)
        leaveCompletely(BOB, roomId)
        leaveCompletely(ALICE, roomId)
        assertThat(room(roomId, DAVE).ownerId).isNull()
        assertThat(emptySince(roomId)).isTrue()

        val room = join(DAVE, roomId)

        assertThat(room.ownerId).isEqualTo(DAVE)
        assertThat(room.members.single().role).isEqualTo(RoomRole.PLAYER)
        assertThat(emptySince(roomId)).isFalse()
    }

    @Test
    fun `leaving a room you are not in is rejected`() {
        val roomId = createRoom(ALICE).id

        call(HttpMethod.POST, "/api/rooms/$roomId/leave", BOB).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("NOT_ROOM_MEMBER") }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roleChanges")
    fun `changing role`(case: String, setup: RoomSetup, user: UUID, role: RoomRole, expectedStatus: HttpStatus, expectedCode: String?) {
        val roomId = with(setup) { prepare() }

        call(HttpMethod.PUT, "/api/rooms/$roomId/role", user, ChangeRoleRequest(role)).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            if (expectedCode != null) jsonPath("$.code") { value(expectedCode) }
        }

        if (expectedStatus == HttpStatus.OK) {
            assertThat(room(roomId, user).members.single { it.userId == user }.role).isEqualTo(role)
        }
    }

    @Test
    fun `owner can become a spectator and keeps ownership`() {
        val roomId = roomWith(ALICE, BOB)

        val room = changeRole(ALICE, roomId, RoomRole.SPECTATOR)

        assertThat(room.ownerId).isEqualTo(ALICE)
        assertThat(room.members.single { it.userId == ALICE }.role).isEqualTo(RoomRole.SPECTATOR)
    }

    @ParameterizedTest(name = "deleted by {0}")
    @MethodSource("deleters")
    fun `only the owner can delete the room`(case: String, user: UUID, expectedStatus: HttpStatus) {
        val roomId = roomWith(ALICE, BOB, started = true)

        call(HttpMethod.DELETE, "/api/rooms/$roomId", user).andExpect { status { isEqualTo(expectedStatus.value()) } }

        val deleted = expectedStatus == HttpStatus.OK
        assertThat(countRows("room WHERE id = :id", "id" to roomId)).isEqualTo(if (deleted) 0 else 1)
        assertThat(countRows("room_session WHERE room_id = :id", "id" to roomId)).isEqualTo(if (deleted) 0 else 1)
    }

    @Test
    fun `cleanup deletes only rooms that stayed empty longer than the ttl`() {
        val longEmpty = roomWith(ALICE).also { leaveCompletely(ALICE, it) }
        val recentlyEmpty = roomWith(BOB).also { leaveCompletely(BOB, it) }
        val occupied = roomWith(CAROL)
        markEmptyMinutesAgo(longEmpty, 31)
        markEmptyMinutesAgo(recentlyEmpty, 29)

        val deleted = emptyRoomCleanupJob.run()

        assertThat(deleted).isEqualTo(1)
        assertThat(countRows("room WHERE id = :id", "id" to longEmpty)).isZero()
        assertThat(countRows("room WHERE id IN (:ids)", "ids" to listOf(recentlyEmpty, occupied))).isEqualTo(2)
    }

    private fun emptySince(roomId: UUID): Boolean =
        countRows("room WHERE id = :id AND empty_since IS NOT NULL", "id" to roomId) == 1L

    private fun markEmptyMinutesAgo(roomId: UUID, minutes: Int) {
        jdbcClient.sql("UPDATE room SET empty_since = now() - make_interval(mins => :minutes) WHERE id = :id")
            .param("id", roomId)
            .param("minutes", minutes)
            .update()
    }

    companion object {
        @JvmStatic
        fun roomOperations(): Stream<Arguments> = Stream.of(
            arguments(HttpMethod.GET, "/api/rooms/{id}", null),
            arguments(HttpMethod.POST, "/api/rooms/{id}/join", null),
            arguments(HttpMethod.POST, "/api/rooms/{id}/leave", null),
            arguments(HttpMethod.PUT, "/api/rooms/{id}/role", ChangeRoleRequest(RoomRole.SPECTATOR)),
            arguments(HttpMethod.DELETE, "/api/rooms/{id}", null),
            arguments(HttpMethod.POST, "/api/rooms/{id}/session", null),
            arguments(HttpMethod.DELETE, "/api/rooms/{id}/session", null),
            arguments(HttpMethod.POST, "/api/rooms/{id}/session/pause", null),
            arguments(HttpMethod.POST, "/api/rooms/{id}/session/resume", null),
            arguments(HttpMethod.POST, "/api/games/nothing/rooms/{id}/moves", MoveRequest(PASS)),
            arguments(HttpMethod.PUT, "/api/games/nothing/rooms/{id}/settings", NOTHING_DEFAULT_SETTINGS),
        )

        @JvmStatic
        fun joins(): Stream<Arguments> = Stream.of(
            arguments("free seat while waiting", RoomSetup { roomWith(ALICE) }, BOB, RoomRole.PLAYER),
            arguments("all seats taken", RoomSetup { roomWith(ALICE, BOB, CAROL, DAVE) }, EVE, RoomRole.SPECTATOR),
            arguments("game in progress", RoomSetup { roomWith(ALICE, started = true) }, BOB, RoomRole.SPECTATOR),
            arguments(
                "player of the running game returns to their seat",
                RoomSetup { roomWith(ALICE, BOB, started = true).also { leaveCompletely(BOB, it) } },
                BOB, RoomRole.PLAYER,
            ),
            arguments(
                "repeated join changes nothing",
                RoomSetup { roomWith(ALICE, BOB).also { changeRole(BOB, it, RoomRole.SPECTATOR) } },
                BOB, RoomRole.SPECTATOR,
            ),
        )

        @JvmStatic
        fun ownerChanges(): Stream<Arguments> = Stream.of(
            arguments(
                "players are preferred over earlier spectators",
                RoomSetup { roomWith(ALICE, CAROL, BOB).also { changeRole(CAROL, it, RoomRole.SPECTATOR) } },
                ALICE, BOB,
            ),
            arguments(
                "earliest player wins",
                RoomSetup { roomWith(ALICE, BOB, CAROL) },
                ALICE, BOB,
            ),
            arguments(
                "only spectators remain",
                RoomSetup { roomWith(ALICE, CAROL).also { changeRole(CAROL, it, RoomRole.SPECTATOR) } },
                ALICE, CAROL,
            ),
            arguments(
                "owner leaves during the game",
                RoomSetup { roomWith(ALICE, BOB, started = true) },
                ALICE, BOB,
            ),
            arguments("not the owner leaves", RoomSetup { roomWith(ALICE, BOB) }, BOB, ALICE),
            arguments("the last one leaves", RoomSetup { roomWith(ALICE) }, ALICE, null),
        )

        @JvmStatic
        fun roleChanges(): Stream<Arguments> = Stream.of(
            arguments("player becomes spectator", RoomSetup { roomWith(ALICE, BOB) }, BOB, RoomRole.SPECTATOR, HttpStatus.OK, null),
            arguments(
                "spectator takes a free seat",
                RoomSetup { roomWith(ALICE, BOB).also { changeRole(BOB, it, RoomRole.SPECTATOR) } },
                BOB, RoomRole.PLAYER, HttpStatus.OK, null,
            ),
            arguments(
                "no free seat",
                RoomSetup { roomWith(ALICE, BOB, CAROL, DAVE, EVE) },
                EVE, RoomRole.PLAYER, HttpStatus.CONFLICT, "NO_FREE_SEAT",
            ),
            arguments(
                "game in progress",
                RoomSetup { roomWith(ALICE, BOB, started = true) },
                BOB, RoomRole.SPECTATOR, HttpStatus.CONFLICT, "ROOM_NOT_WAITING",
            ),
            arguments("not in the room", RoomSetup { roomWith(ALICE) }, BOB, RoomRole.PLAYER, HttpStatus.FORBIDDEN, "NOT_ROOM_MEMBER"),
        )

        @JvmStatic
        fun deleters(): Stream<Arguments> = Stream.of(
            arguments("owner", ALICE, HttpStatus.OK),
            arguments("another member", BOB, HttpStatus.FORBIDDEN),
            arguments("a stranger", EVE, HttpStatus.FORBIDDEN),
        )
    }
}
