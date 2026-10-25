package site.qgames.userauth.controller.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.test.web.servlet.patch
import org.springframework.http.MediaType
import site.qgames.userauth.UserAuthIntegrationTest
import site.qgames.userauth.api.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.userauth.api.dto.user.UserResponse
import site.qgames.userauth.support.GoogleIdTokens
import site.qgames.userauth.support.TestData
import java.util.UUID
import java.util.stream.Stream
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import site.qgames.userauth.table.user.UserTable

class ProfileScenariosTest : UserAuthIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileUpdates")
    fun `profile update changes only passed fields and publishes a new snapshot`(
        case: String,
        body: Map<String, Any?>,
        expectedNickname: String,
        expectedAvatar: Avatar,
    ) {
        val response = patchProfile(body, TestData.PasswordOnly.ID).andExpect { status { isOk() } }.body<UserResponse>()

        assertThat(response.nickname).isEqualTo(expectedNickname)
        assertThat(response.avatar).isEqualTo(expectedAvatar)
        val stored = db {
            UserTable.selectAll().where { UserTable.id eq TestData.PasswordOnly.ID }.single()
                .let { it[UserTable.nickname] to it[UserTable.avatar] }
        }
        assertThat(stored).isEqualTo(expectedNickname to expectedAvatar.name)

        val event = publishedProfileEvents().single()
        assertThat(event.id).isEqualTo(TestData.PasswordOnly.ID)
        assertThat(event.nickname).isEqualTo(expectedNickname)
        assertThat(event.avatar).isEqualTo(expectedAvatar)
    }

    @Test
    fun `every profile change publishes its own event`() {
        patchProfile(mapOf("nickname" to "First"), TestData.PasswordOnly.ID).andExpect { status { isOk() } }
        patchProfile(mapOf("nickname" to "Second"), TestData.PasswordOnly.ID).andExpect { status { isOk() } }

        assertThat(publishedProfileEvents().map { it.nickname }).containsExactly("First", "Second")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUpdates")
    fun `invalid profile update changes nothing and publishes nothing`(case: String, body: Map<String, Any?>) {
        patchProfile(body, TestData.PasswordOnly.ID).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }

        assertThat(publishedProfileEvents()).isEmpty()
        getJson("/api/users/me", TestData.PasswordOnly.ID).andExpect { jsonPath("$.nickname") { value("Player") } }
    }

    @Test
    fun `update with the same values publishes nothing`() {
        patchProfile(mapOf("nickname" to "Player", "avatar" to "CAT"), TestData.PasswordOnly.ID).andExpect { status { isOk() } }

        assertThat(publishedProfileEvents()).isEmpty()
    }

    @Test
    fun `nickname is not unique`() {
        patchProfile(mapOf("nickname" to "Both"), TestData.PasswordOnly.ID).andExpect {
            status { isOk() }
            jsonPath("$.nickname") { value("Both") }
        }
    }

    @Test
    fun `profile update without access token is unauthenticated`() {
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
            .session()

        assertThat(auth.user.nickname).matches("player-[0-9a-f]{6}")
        assertThat(auth.user.avatar).isEqualTo(Avatar.CAT)
        val event = publishedProfileEvents().single()
        assertThat(event.id).isEqualTo(auth.user.id)
        assertThat(event.nickname).isEqualTo(auth.user.nickname)
        assertThat(event.avatar).isEqualTo(Avatar.CAT)
    }

    @Test
    fun `linking google to an existing account publishes nothing`() {
        postJson(
            "/api/users/me/login-methods/google",
            GoogleIdTokenRequest(GoogleIdTokens.idToken("google-new", "other@gmail.com")),
            TestData.PasswordOnly.ID,
        ).andExpect { status { isOk() } }

        assertThat(publishedProfileEvents()).isEmpty()
    }

    private fun patchProfile(body: Map<String, Any?>, userId: UUID?) =
        mockMvc.patch("/api/users/me/profile") {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
            asUser(userId)
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
