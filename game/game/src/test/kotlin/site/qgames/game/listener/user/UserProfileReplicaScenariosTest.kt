package site.qgames.game.listener.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import site.qgames.game.BaseIntegrationTest
import site.qgames.game.gatewayapi.dto.room.Avatar
import site.qgames.game.support.TestData
import site.qgames.userauth.asyncapi.event.UserProfileChanged
import java.util.UUID
import site.qgames.userauth.asyncapi.event.Avatar as AvatarProto

/**
 * Реплика user_auth."user" (таблица "user_auth#user"): события приходят в топик так же, как их публикует Debezium.
 * Топик в тестах с одной партицией, поэтому «маркерное» событие после проверяемого гарантирует, что проверяемое уже обработано.
 */
class UserProfileReplicaScenariosTest : BaseIntegrationTest() {

    @Test
    fun `new user appears in the replica`() {
        val userId = UUID.randomUUID()

        publishProfile(userId, "Newbie", AvatarProto.AVATAR_DOG)

        awaitUntil("user replicated") { storedNickname(userId) == "Newbie" }
        val row = jdbcClient.sql("""SELECT avatar, version FROM "user_auth#user" WHERE id = :id""").param("id", userId).query().singleRow()
        assertThat(row).containsEntry("avatar", "DOG").containsEntry("version", 0L)
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
        awaitMarker()

        assertThat(storedNickname(userId)).isEqualTo(expectedNickname)
    }

    @Test
    fun `broken messages are skipped and do not block the topic`() {
        val userId = UUID.randomUUID()
        publishRaw("garbage", byteArrayOf(0x0a, 0x7f, 0x01))
        publishRaw(userId.toString(), UserProfileChanged.newBuilder().setUserId(userId.toString()).setNickname("NoAvatar").build().toByteArray())

        publishProfile(userId, "Valid")

        awaitUntil("valid event after broken ones applied") { storedNickname(userId) == "Valid" }
    }

    @Test
    fun `room shows the nickname as soon as the user is replicated`() {
        val roomId = createRoom(TestData.STRANGER).id
        assertThat(room(roomId, TestData.STRANGER).members.single().nickname).isNull()

        publishProfile(TestData.STRANGER, "Late Bird", AvatarProto.AVATAR_DOG)
        awaitUntil("stranger replicated") { storedNickname(TestData.STRANGER) != null }

        val member = room(roomId, TestData.STRANGER).members.single()
        assertThat(member.nickname).isEqualTo("Late Bird")
        assertThat(member.avatar).isEqualTo(Avatar.DOG)
    }

    private fun awaitMarker() {
        val marker = UUID.randomUUID()
        publishProfile(marker, "Marker")
        awaitUntil("marker replicated") { storedNickname(marker) != null }
    }
}
