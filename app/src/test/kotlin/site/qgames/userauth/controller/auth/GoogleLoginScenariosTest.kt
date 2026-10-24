package site.qgames.userauth.controller.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.userauth.UserAuthIntegrationTest
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.userauth.api.dto.user.LoginMethod
import site.qgames.userauth.support.GoogleIdTokens
import site.qgames.userauth.support.TestData
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

class GoogleLoginScenariosTest : UserAuthIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    fun `untrusted google token is rejected and creates nothing`(case: String, idToken: String) {
        loginWithGoogle(idToken).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_GOOGLE_TOKEN") }
        }
        assertThat(countRows("\"user_auth#user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
        assertThat(countRows("\"user_auth#user_identity\" WHERE subject = '$NEW_SUB'")).isZero()
    }

    @Test
    fun `first google login creates an account without password and next logins reuse it`() {
        val idToken = GoogleIdTokens.idToken(subject = NEW_SUB, email = "Newcomer@Gmail.com")

        val first = loginWithGoogle(idToken).andExpect { status { isOk() } }.session()
        val second = loginWithGoogle(idToken).andExpect { status { isOk() } }.session()

        assertThat(first.user.email).isEqualTo("newcomer@gmail.com")
        assertThat(first.user.nickname).matches("player-[0-9a-f]{6}")
        assertThat(first.user.avatar).isEqualTo(Avatar.CAT)
        assertThat(first.user.loginMethods).containsExactly(LoginMethod.GOOGLE)
        assertThat(second.user.id).isEqualTo(first.user.id)
        assertThat(countRows("\"user_auth#user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT + 1)
        assertThat(publishedProfileEvents().map { it.id }).containsExactly(first.user.id)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("linkedGoogleAccounts")
    fun `google account linked to a user signs into that user even if google email changed`(
        case: String,
        subject: String,
        expectedUserId: UUID,
    ) {
        val auth = loginWithGoogle(GoogleIdTokens.idToken(subject = subject, email = "renamed@gmail.com"))
            .andExpect { status { isOk() } }
            .session()

        assertThat(auth.user.id).isEqualTo(expectedUserId)
        assertThat(countRows("\"user_auth#user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
    }

    @Test
    fun `google account with unverified email cannot sign in`() {
        loginWithGoogle(GoogleIdTokens.idToken(subject = NEW_SUB, email = "newcomer@gmail.com", emailVerified = false)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("GOOGLE_EMAIL_NOT_VERIFIED") }
        }
        assertThat(countRows("\"user_auth#user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("existingAccounts")
    fun `unlinked google account with an email of an existing account is not linked automatically`(
        case: String,
        email: String,
        userId: UUID,
    ) {
        val methodsBefore = loginMethodsOf(userId)

        loginWithGoogle(GoogleIdTokens.idToken(subject = NEW_SUB, email = email)).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ACCOUNT_LINK_REQUIRED") }
        }

        assertThat(countRows("\"user_auth#user_identity\" WHERE subject = '$NEW_SUB'")).isZero()
        assertThat(loginMethodsOf(userId)).isEqualTo(methodsBefore)
        assertThat(countRows("\"user_auth#user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
        assertThat(countRows("\"user_auth#refresh_token\"")).isZero()
        assertThat(publishedProfileEvents()).isEmpty()
    }

    private fun loginMethodsOf(userId: UUID): List<String> =
        jdbcClient.sql("SELECT provider FROM \"user_auth#user_identity\" WHERE user_id = :userId ORDER BY provider")
            .param("userId", userId)
            .query(String::class.java)
            .list()
            .filterNotNull()

    companion object {
        private const val NEW_SUB = "google-new"

        @JvmStatic
        fun invalidTokens(): Stream<Arguments> = Stream.of(
            arguments("issued for another client", GoogleIdTokens.idToken(NEW_SUB, "newcomer@gmail.com", audience = "someone-else")),
            arguments("issued by another issuer", GoogleIdTokens.idToken(NEW_SUB, "newcomer@gmail.com", issuer = "https://evil.example.com")),
            arguments("expired", GoogleIdTokens.idToken(NEW_SUB, "newcomer@gmail.com", expiresAt = Instant.now().minusSeconds(3600))),
            arguments("signed by unknown key", GoogleIdTokens.idToken(NEW_SUB, "newcomer@gmail.com", signedByForeignKey = true)),
            arguments("not a jwt at all", "definitely-not-a-jwt"),
        )

        @JvmStatic
        fun linkedGoogleAccounts(): Stream<Arguments> = Stream.of(
            arguments("google-only account", TestData.GoogleOnly.GOOGLE_SUB, TestData.GoogleOnly.ID),
            arguments("account with password and google", TestData.PasswordAndGoogle.GOOGLE_SUB, TestData.PasswordAndGoogle.ID),
        )

        @JvmStatic
        fun existingAccounts(): Stream<Arguments> = Stream.of(
            arguments("password-only account", TestData.PasswordOnly.EMAIL, TestData.PasswordOnly.ID),
            arguments("account linked to another google account", TestData.PasswordAndGoogle.EMAIL, TestData.PasswordAndGoogle.ID),
            arguments("google-only account linked to another google account", TestData.GoogleOnly.EMAIL, TestData.GoogleOnly.ID),
            arguments("email in different case", "PLAYER@Example.com", TestData.PasswordOnly.ID),
        )
    }
}
