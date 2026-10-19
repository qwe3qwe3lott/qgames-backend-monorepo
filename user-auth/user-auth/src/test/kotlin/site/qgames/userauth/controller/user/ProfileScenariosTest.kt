package site.qgames.userauth.controller.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.test.web.servlet.patch
import org.springframework.http.MediaType
import site.qgames.userauth.BaseIntegrationTest
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.gatewayapi.dto.user.Avatar
import site.qgames.userauth.gatewayapi.dto.user.UserResponse
import site.qgames.userauth.support.GoogleIdTokens
import site.qgames.userauth.support.TestData
import java.util.UUID
import java.util.stream.Stream
import site.qgames.userauth.asyncapi.event.Avatar as AvatarProto

class ProfileScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileUpdates")
    fun `profile update changes only passed fields and publishes a new snapshot`(
        case: String,
        body: Map<String, Any?>,
        expectedNickname: String,
        expectedAvatar: Avatar,
    ) {
        val response = patchProfile(body, TestData.Verified.ID).andExpect { status { isOk() } }.body<UserResponse>()

        assertThat(response.nickname).isEqualTo(expectedNickname)
        assertThat(response.avatar).isEqualTo(expectedAvatar)
        val stored = jdbcClient.sql("""SELECT nickname, avatar FROM "user" WHERE id = :id""")
            .param("id", TestData.Verified.ID)
            .query()
            .singleRow()
        assertThat(stored).containsEntry("nickname", expectedNickname).containsEntry("avatar", expectedAvatar.name)

        val event = publishedProfileEvents().single()
        assertThat(event.userId).isEqualTo(TestData.Verified.ID.toString())
        assertThat(event.nickname).isEqualTo(expectedNickname)
        assertThat(event.avatar).isEqualTo(AvatarProto.valueOf("AVATAR_${expectedAvatar.name}"))
        assertThat(event.version).isEqualTo(1).isEqualTo(storedVersion(TestData.Verified.ID))
    }

    @Test
    fun `every profile change increases the version carried by the event`() {
        patchProfile(mapOf("nickname" to "First"), TestData.Verified.ID).andExpect { status { isOk() } }
        patchProfile(mapOf("nickname" to "Second"), TestData.Verified.ID).andExpect { status { isOk() } }

        assertThat(publishedProfileEvents().map { it.nickname to it.version }).containsExactly("First" to 1L, "Second" to 2L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUpdates")
    fun `invalid profile update changes nothing and publishes nothing`(case: String, body: Map<String, Any?>) {
        patchProfile(body, TestData.Verified.ID).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }

        assertThat(publishedProfileEvents()).isEmpty()
        getJson("/api/users/me", TestData.Verified.ID).andExpect { jsonPath("$.nickname") { value("Player") } }
    }

    @Test
    fun `update with the same values publishes nothing`() {
        patchProfile(mapOf("nickname" to "Player", "avatar" to "CAT"), TestData.Verified.ID).andExpect { status { isOk() } }

        assertThat(publishedProfileEvents()).isEmpty()
        assertThat(storedVersion(TestData.Verified.ID)).isZero()
    }

    @Test
    fun `nickname is not unique`() {
        patchProfile(mapOf("nickname" to "Both"), TestData.Verified.ID).andExpect {
            status { isOk() }
            jsonPath("$.nickname") { value("Both") }
        }
    }

    @Test
    fun `profile update without user header is unauthenticated`() {
        patchProfile(mapOf("nickname" to "Nobody"), userId = null).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `unknown user cannot update profile`() {
        patchProfile(mapOf("nickname" to "Ghost"), UUID.randomUUID()).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("USER_NOT_FOUND") }
        }
    }

    @Test
    fun `new google account gets a generated nickname, default avatar and a published snapshot`() {
        val auth = loginWithGoogle(GoogleIdTokens.idToken("google-new", "newcomer@gmail.com"))
            .andExpect { status { isOk() } }
            .body<AuthResponse>()

        assertThat(auth.user.nickname).matches("player-[0-9a-f]{6}")
        assertThat(auth.user.avatar).isEqualTo(Avatar.CAT)
        val event = publishedProfileEvents().single()
        assertThat(event.userId).isEqualTo(auth.user.id.toString())
        assertThat(event.nickname).isEqualTo(auth.user.nickname)
        assertThat(event.avatar).isEqualTo(AvatarProto.AVATAR_CAT)
        assertThat(event.version).isZero()
    }

    @Test
    fun `linking google to an existing account publishes nothing`() {
        loginWithGoogle(GoogleIdTokens.idToken("google-new", TestData.Verified.EMAIL)).andExpect { status { isOk() } }

        assertThat(publishedProfileEvents()).isEmpty()
    }

    private fun storedVersion(userId: UUID): Long =
        jdbcClient.sql("""SELECT version FROM "user" WHERE id = :id""").param("id", userId).query(Long::class.java).single()

    private fun patchProfile(body: Map<String, Any?>, userId: UUID?) =
        mockMvc.patch("/api/users/me/profile") {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
            userId?.let { header("X-User-Id", it.toString()) }
        }

    companion object {
        @JvmStatic
        fun profileUpdates(): Stream<Arguments> = Stream.of(
            arguments("nickname only", mapOf("nickname" to "Renamed"), "Renamed", Avatar.CAT),
            arguments("avatar only", mapOf("avatar" to "DOG"), "Player", Avatar.DOG),
            arguments("both", mapOf("nickname" to "Кот Борис", "avatar" to "DOG"), "Кот Борис", Avatar.DOG),
        )

        @JvmStatic
        fun invalidUpdates(): Stream<Arguments> = Stream.of(
            arguments("empty body", emptyMap<String, Any?>()),
            arguments("too short nickname", mapOf("nickname" to "ab")),
            arguments("nickname with trailing space", mapOf("nickname" to "Bob ")),
            arguments("too long nickname", mapOf("nickname" to "n".repeat(33))),
        )
    }
}
