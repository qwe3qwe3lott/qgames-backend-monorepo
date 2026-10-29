package site.qgames.game.listener.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import site.qgames.game.GameIntegrationTest
import site.qgames.game.api.dto.room.RoomEventPatch
import site.qgames.game.support.TestData.ALICE
import site.qgames.game.support.TestData.BOB
import site.qgames.game.support.TestData.CAROL
import site.qgames.game.support.TestData.STRANGER
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.game.api.dto.room.Avatar as RoomAvatar

/**
 * Профили в комнатах: ник и аватар game читает из таблицы user модуля user-auth (join, только чтение). Смена профиля
 * в user-auth (событие UserProfileChanged после коммита) рассылает profile-changed во все комнаты пользователя.
 */
class UserProfileScenariosTest : GameIntegrationTest() {

    @Test
    fun `profile change sends profile-changed to every room of the user and shows in the room snapshot`() {
        val first = roomWith(ALICE, BOB)
        val second = roomWith(CAROL, BOB)
        val firstStream = openStream(ALICE, first)
        val secondStream = openStream(CAROL, second)
        awaitEvents(firstStream, 1)
        awaitEvents(secondStream, 1)

        updateProfile(BOB, "Bobby", Avatar.CAT)

        listOf(firstStream, secondStream).forEach { stream ->
            val (type, data) = awaitEvents(stream, 2)[1]
            assertThat(type).isEqualTo("patch")
            val event = jsonMapper.readValue(data, RoomEventPatch::class.java)
            assertThat(event.code).isEqualTo("profile-changed")
            assertThat(event.data.path).isEqualTo(listOf("members", mapOf("id" to BOB.toString())))
            assertThat(event.data.value).isEqualTo(mapOf("nickname" to "Bobby", "avatar" to "CAT"))
        }
        val bob = room(first, ALICE).members.single { it.id == BOB }
        assertThat(bob.nickname).isEqualTo("Bobby")
        assertThat(bob.avatar).isEqualTo(RoomAvatar.CAT)
    }

    @Test
    fun `members are shown with the profile from the user table`() {
        val roomId = roomWith(ALICE, BOB)

        val members = room(roomId, ALICE).members

        assertThat(members.map { Triple(it.id, it.nickname, it.avatar) })
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
