package site.qgames.userauth.controller.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.userauth.UserAuthIntegrationTest
import site.qgames.userauth.api.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.api.dto.user.LoginMethod
import site.qgames.userauth.api.dto.user.SetPasswordRequest
import site.qgames.userauth.api.dto.user.UserResponse
import site.qgames.userauth.support.GoogleIdTokens
import site.qgames.userauth.support.TestData
import java.util.UUID
import java.util.stream.Stream
import site.qgames.userauth.table.user.UserIdentityTable

class   LoginMethodsScenariosTest : UserAuthIntegrationTest() {

    @Test
    fun `google-only user adds a password and can sign in with it`() {
        val updated = postJson(PASSWORD_PATH, SetPasswordRequest("brand-new-password"), TestData.GoogleOnly.ID)
            .andExpect { status { isOk() } }
            .body<UserResponse>()

        assertThat(updated.loginMethods).containsExactly(LoginMethod.PASSWORD, LoginMethod.GOOGLE)
        val byPassword = login(TestData.GoogleOnly.EMAIL, "brand-new-password").andExpect { status { isOk() } }.session()
        assertThat(byPassword.user.id).isEqualTo(TestData.GoogleOnly.ID)
    }

    @Test
    fun `password user links google with a different email and can sign in with it`() {
        val idToken = GoogleIdTokens.idToken("google-new", "another.address@gmail.com")

        postJson(GOOGLE_PATH, GoogleIdTokenRequest(idToken), TestData.PasswordOnly.ID).andExpect { status { isOk() } }

        val byGoogle = loginWithGoogle(idToken).andExpect { status { isOk() } }.session()
        assertThat(byGoogle.user.id).isEqualTo(TestData.PasswordOnly.ID)
        assertThat(byGoogle.user.email).isEqualTo(TestData.PasswordOnly.EMAIL)
        assertThat(byGoogle.user.loginMethods).containsExactly(LoginMethod.PASSWORD, LoginMethod.GOOGLE)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conflicts")
    fun `linking is rejected`(case: String, userId: UUID, path: String, body: Any, expectedCode: String) {
        val identitiesBefore = count(UserIdentityTable)

        postJson(path, body, userId).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value(expectedCode) }
        }
        assertThat(count(UserIdentityTable)).isEqualTo(identitiesBefore)
    }

    @Test
    fun `request without access token is unauthenticated`() {
        getJson("/api/users/me").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `unknown user id yields not found`() {
        getJson("/api/users/me", UUID.randomUUID()).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("USER_NOT_FOUND") }
        }
    }

    companion object {
        private const val PASSWORD_PATH = "/api/users/me/login-methods/password"
        private const val GOOGLE_PATH = "/api/users/me/login-methods/google"

        @JvmStatic
        fun conflicts(): Stream<Arguments> = Stream.of(
            arguments(
                "password is already set",
                TestData.PasswordAndGoogle.ID, PASSWORD_PATH, SetPasswordRequest("new-password-1"),
                "LOGIN_METHOD_ALREADY_LINKED",
            ),
            arguments(
                "another google account is already linked",
                TestData.PasswordAndGoogle.ID, GOOGLE_PATH, GoogleIdTokenRequest(GoogleIdTokens.idToken("google-new", "second@gmail.com")),
                "LOGIN_METHOD_ALREADY_LINKED",
            ),
            arguments(
                "google account belongs to another user",
                TestData.PasswordOnly.ID, GOOGLE_PATH,
                GoogleIdTokenRequest(GoogleIdTokens.idToken(TestData.GoogleOnly.GOOGLE_SUB, TestData.GoogleOnly.EMAIL)),
                "GOOGLE_ACCOUNT_ALREADY_LINKED",
            ),
        )
    }
}
