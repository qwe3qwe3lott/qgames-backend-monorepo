package site.qgames.userauth.controller.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import site.qgames.userauth.BaseIntegrationTest
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.gatewayapi.dto.user.LoginMethod
import site.qgames.userauth.support.GoogleIdTokens
import site.qgames.userauth.support.TestData
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

class GoogleLoginScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    fun `untrusted google token is rejected and creates nothing`(case: String, idToken: String) {
        loginWithGoogle(idToken).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_GOOGLE_TOKEN") }
        }
        assertThat(countRows("\"user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
        assertThat(countRows("user_identity WHERE subject = '$NEW_SUB'")).isZero()
    }

    @Test
    fun `first google login creates a verified account without password and next logins reuse it`() {
        val idToken = GoogleIdTokens.idToken(subject = NEW_SUB, email = "Newcomer@Gmail.com")

        val first = loginWithGoogle(idToken).andExpect { status { isOk() } }.body<AuthResponse>()
        val second = loginWithGoogle(idToken).andExpect { status { isOk() } }.body<AuthResponse>()

        assertThat(first.user.email).isEqualTo("newcomer@gmail.com")
        assertThat(first.user.emailVerified).isTrue()
        assertThat(first.user.loginMethods).containsExactly(LoginMethod.GOOGLE)
        assertThat(second.user.id).isEqualTo(first.user.id)
        assertThat(countRows("\"user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT + 1)
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
            .body<AuthResponse>()

        assertThat(auth.user.id).isEqualTo(expectedUserId)
        assertThat(countRows("\"user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
    }

    @Test
    fun `google account with unverified email cannot sign in`() {
        loginWithGoogle(GoogleIdTokens.idToken(subject = NEW_SUB, email = "newcomer@gmail.com", emailVerified = false)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("GOOGLE_EMAIL_NOT_VERIFIED") }
        }
        assertThat(countRows("\"user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("existingAccounts")
    fun `google login against an existing account with the same email`(
        case: String,
        email: String,
        expectedStatus: HttpStatus,
        expectedCode: String?,
        expectedUserId: UUID?,
    ) {
        val result = loginWithGoogle(GoogleIdTokens.idToken(subject = NEW_SUB, email = email)).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            if (expectedCode != null) jsonPath("$.code") { value(expectedCode) }
        }

        if (expectedUserId != null) {
            val auth = result.body<AuthResponse>()
            assertThat(auth.user.id).isEqualTo(expectedUserId)
            assertThat(auth.user.loginMethods).containsExactly(LoginMethod.PASSWORD, LoginMethod.GOOGLE)
            login(email).andExpect { status { isOk() } }
        } else {
            assertThat(countRows("user_identity WHERE subject = '$NEW_SUB'")).isZero()
        }
        assertThat(countRows("\"user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
    }

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
            arguments(
                "verified password account is linked automatically",
                TestData.Verified.EMAIL, HttpStatus.OK, null, TestData.Verified.ID,
            ),
            arguments(
                "unverified password account requires explicit linking",
                TestData.Unverified.EMAIL, HttpStatus.CONFLICT, "ACCOUNT_LINK_REQUIRED", null,
            ),
            arguments(
                "account already linked to another google account requires explicit linking",
                TestData.PasswordAndGoogle.EMAIL, HttpStatus.CONFLICT, "ACCOUNT_LINK_REQUIRED", null,
            ),
        )
    }
}
