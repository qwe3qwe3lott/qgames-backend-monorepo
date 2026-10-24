package site.qgames.game.listener.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import site.qgames.game.GameIntegrationTest
import site.qgames.game.support.TestData
import site.qgames.userauth.api.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.userauth.support.GoogleIdTokens
import java.util.UUID
import site.qgames.game.api.dto.room.Avatar as RoomAvatar
import site.qgames.userauth.support.TestData as UserAuthTestData

/**
 * Реплика `"user_auth#user"` в `"game#user_auth#user"`: событие модуля user-auth UserProfileChanged доставляется
 * Spring Modulith после коммита (реестр event_publication), слушатель game применяет его идемпотентно по version.
 */
class UserProfileReplicaScenariosTest : GameIntegrationTest() {

    @Test
    fun `profile change in user-auth reaches the replica of game`() {
        val userId = UserAuthTestData.PasswordOnly.ID
        assertThat(storedNickname(userId)).isNull()

        mockMvc.patch("/api/users/me/profile") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname": "Renamed", "avatar": "DOG"}"""
            asUser(userId)
        }.andExpect { status { isOk() } }

        awaitUntil("profile replicated") { storedNickname(userId) == "Renamed" }
        val row = jdbcClient.sql("""SELECT avatar, version FROM "game#user_auth#user" WHERE id = :id""").param("id", userId).query().singleRow()
        assertThat(row).containsEntry("avatar", "DOG").containsEntry("version", 1L)
        awaitEventsDelivered()
    }

    @Test
    fun `account created by google login appears in the replica`() {
        val user = mockMvc.post("/api/auth/google") {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(GoogleIdTokenRequest(GoogleIdTokens.idToken("google-new", "newcomer@gmail.com")))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString.let { jsonMapper.readTree(it) }

        val userId = UUID.fromString(user["id"].asString())
        awaitUntil("new account replicated") { storedNickname(userId) == user["nickname"].asString() }
    }

    @ParameterizedTest(name = "second event version {0} → nickname {1}")
    @CsvSource(
        "2, Second",
        "0, First",
        "1, First",
    )
    fun `only a snapshot with a greater version overwrites the replica`(secondVersion: Long, expectedNickname: String) {
        val userId = UUID.randomUUID()
        publishProfile(userId, "First", version = 1)
        awaitUntil("first snapshot replicated") { storedNickname(userId) == "First" }

        publishProfile(userId, "Second", version = secondVersion)
        awaitEventsDelivered()

        assertThat(storedNickname(userId)).isEqualTo(expectedNickname)
    }

    @Test
    fun `room shows the nickname as soon as the user is replicated`() {
        val roomId = createRoom(TestData.STRANGER).id
        assertThat(room(roomId, TestData.STRANGER).members.single().nickname).isNull()

        publishProfile(TestData.STRANGER, "Late Bird", Avatar.DOG)
        awaitUntil("stranger replicated") { storedNickname(TestData.STRANGER) != null }

        val member = room(roomId, TestData.STRANGER).members.single()
        assertThat(member.nickname).isEqualTo("Late Bird")
        assertThat(member.avatar).isEqualTo(RoomAvatar.DOG)
    }
}
