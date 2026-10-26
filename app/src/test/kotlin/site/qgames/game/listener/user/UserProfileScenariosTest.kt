package site.qgames.game.listener.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.RoomUsersDto
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.support.TestData.STRANGER
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.game.api.dto.room.Avatar as RoomAvatar

/**
 * Профили в комнатах: ник и аватар game читает из таблицы user модуля user-auth (join, только чтение). Смена профиля
 * в user-auth (событие UserProfileChanged после коммита) рассылает users-changed во все комнаты пользователя.
 */
class UserProfileScenariosTest : GameIntegrationTest() {

    @Test
    fun `profile change sends users-changed to every room of the user and shows in the room snapshot`() {
        val first = roomWith(ALICE, BOB)
        val second = roomWith(CAROL, BOB)
        val firstStream = openStream(ALICE, first)
        val secondStream = openStream(CAROL, second)
        awaitEvents(firstStream, 1)
        awaitEvents(secondStream, 1)

        updateProfile(BOB, "Bobby", Avatar.CAT)

        listOf(firstStream, secondStream).forEach { stream ->
            val (type, data) = awaitEvents(stream, 2)[1]
            assertThat(type).isEqualTo("users-changed")
            val bob = jsonMapper.readValue(data, RoomUsersDto::class.java).members.single { it.userId == BOB }
            assertThat(bob.nickname).isEqualTo("Bobby")
            assertThat(bob.avatar).isEqualTo(RoomAvatar.CAT)
        }
        val bob = room(first, ALICE).members.single { it.userId == BOB }
        assertThat(bob.nickname).isEqualTo("Bobby")
        assertThat(bob.avatar).isEqualTo(RoomAvatar.CAT)
    }

    @Test
    fun `members are shown with the profile from the user table`() {
        val roomId = roomWith(ALICE, BOB)

        val members = room(roomId, ALICE).members

        assertThat(members.map { Triple(it.userId, it.nickname, it.avatar) })
            .containsExactly(Triple(ALICE, "Alice", RoomAvatar.CAT), Triple(BOB, "Bob", RoomAvatar.DOG))
    }

    @Test
    fun `member without a user row has no profile`() {
        val roomId = createRoom(STRANGER).id

        val member = room(roomId, STRANGER).members.single()

        assertThat(member.nickname).isNull()
        assertThat(member.avatar).isNull()
    }
}
