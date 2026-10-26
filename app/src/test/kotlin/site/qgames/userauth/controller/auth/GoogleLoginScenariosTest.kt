package site.qgames.userauth.controller.auth

import org.assertj.core.api.Assertions.assertThat
import io.mockk.every
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.userauth.UserAuthIntegrationTest
import site.qgames.userauth.api.dto.user.Avatar
import site.qgames.userauth.api.dto.user.LoginMethod
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.support.GoogleIdTokens
import site.qgames.userauth.support.TestData
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream
import org.jetbrains.exposed.v1.core.eq
import site.qgames.userauth.table.user.UserTable
import site.qgames.userauth.table.user.UserIdentityTable
import site.qgames.userauth.table.auth.RefreshTokenTable

/** Вход через Google (только привязанный аккаунт) и регистрация через Google с выбором логина. */
class GoogleLoginScenariosTest : UserAuthIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    fun `untrusted google token is rejected on login and registration and creates nothing`(case: String, idToken: String) {
        loginWithGoogle(idToken).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_GOOGLE_TOKEN") }
        }
        registerWithGoogle(idToken, NEW_LOGIN).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_GOOGLE_TOKEN") }
        }
        assertNothingCreated()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("linkedGoogleAccounts")
    fun `google account linked to a user signs into that user`(case: String, subject: String, expectedUserId: UUID) {
        val auth = loginWithGoogle(GoogleIdTokens.idToken(subject)).andExpect { status { isOk() } }.session()

        assertThat(auth.user.id).isEqualTo(expectedUserId)
        assertThat(count(UserTable)).isEqualTo(TestData.FIXTURE_USER_COUNT)
    }

    @Test
    fun `login with unknown google account requires registration and creates nothing`() {
        loginWithGoogle(GoogleIdTokens.idToken(NEW_SUB)).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("REGISTRATION_REQUIRED") }
        }
        assertNothingCreated()
    }

    @Test
    fun `registration without password creates a google-only account and next google logins reuse it`() {
        val idToken = GoogleIdTokens.idToken(NEW_SUB)

        val registered = registerWithGoogle(idToken, NEW_LOGIN).andExpect { status { isCreated() } }.session()
        val next = loginWithGoogle(idToken).andExpect { status { isOk() } }.session()

        assertThat(registered.user.login).isEqualTo(NEW_LOGIN)
        assertThat(registered.user.nickname).isEqualTo(NEW_LOGIN)
        assertThat(registered.user.avatar).isEqualTo(Avatar.CAT)
        assertThat(registered.user.loginMethods).containsExactly(LoginMethod.GOOGLE)
        assertThat(next.user.id).isEqualTo(registered.user.id)
        assertThat(count(UserTable)).isEqualTo(TestData.FIXTURE_USER_COUNT + 1)
        assertThat(publishedProfileEvents().map { it.id to it.nickname }).containsExactly(registered.user.id to NEW_LOGIN)
        login(NEW_LOGIN, "any-password").andExpect { jsonPath("$.code") { value("INVALID_CREDENTIALS") } }
    }

    @Test
    fun `registration with password also enables login by login and password`() {
        val registered = registerWithGoogle(GoogleIdTokens.idToken(NEW_SUB), NEW_LOGIN, NEW_PASSWORD)
            .andExpect { status { isCreated() } }
            .session()

        assertThat(registered.user.loginMethods).containsExactlyInAnyOrder(LoginMethod.GOOGLE, LoginMethod.PASSWORD)
        val byPassword = login(" New-Player ", NEW_PASSWORD).andExpect { status { isOk() } }.session()
        assertThat(byPassword.user.id).isEqualTo(registered.user.id)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("linkedGoogleAccounts")
    fun `registration with an already registered google account is rejected`(case: String, subject: String, userId: UUID) {
        registerWithGoogle(GoogleIdTokens.idToken(subject), NEW_LOGIN).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ACCOUNT_ALREADY_EXISTS") }
        }
        assertNothingCreated()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("takenLogins")
    fun `registration with a taken login is rejected`(case: String, login: String) {
        registerWithGoogle(GoogleIdTokens.idToken(NEW_SUB), login).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("LOGIN_TAKEN") }
        }
        assertNothingCreated()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRegistrations")
    fun `registration with a login or password out of rules is a validation error`(case: String, login: String, password: String?) {
        registerWithGoogle(GoogleIdTokens.idToken(NEW_SUB), login, password).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }
        assertNothingCreated()
    }

    /**
     * Гонка двух регистраций одного логина: проверка «логин свободен» прошла у обоих, второй упирается в уникальность
     * логина в БД — тот же 409 LOGIN_TAKEN, что и без гонки, и ничего не создаётся.
     */
    @Test
    fun `concurrent registration of the same login is reported as a taken login`() {
        every { userRepository.findByLogin(TestData.PasswordOnly.LOGIN) } returns null

        registerWithGoogle(GoogleIdTokens.idToken(NEW_SUB), TestData.PasswordOnly.LOGIN).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("LOGIN_TAKEN") }
        }
        assertNothingCreated()
    }

    /**
     * Гонка двух регистраций одного Google-аккаунта с разными логинами: проверка «Google не привязан» прошла у обоих,
     * второй упирается в уникальность способа входа — тот же 409 ACCOUNT_ALREADY_EXISTS, аккаунт не создаётся.
     */
    @Test
    fun `concurrent registration of the same google account is reported as an existing account`() {
        every {
            userIdentityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, TestData.GoogleOnly.GOOGLE_SUB)
        } returns null

        registerWithGoogle(GoogleIdTokens.idToken(TestData.GoogleOnly.GOOGLE_SUB), NEW_LOGIN).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ACCOUNT_ALREADY_EXISTS") }
        }
        assertNothingCreated()
        assertThat(count(UserTable) { UserTable.login eq NEW_LOGIN }).isZero()
    }

    private fun assertNothingCreated() {
        assertThat(count(UserTable)).isEqualTo(TestData.FIXTURE_USER_COUNT)
        assertThat(count(UserIdentityTable) { UserIdentityTable.subject eq NEW_SUB }).isZero()
        assertThat(count(RefreshTokenTable)).isZero()
        assertThat(publishedProfileEvents()).isEmpty()
    }

    companion object {
        private const val NEW_SUB = "google-new"
        private const val NEW_LOGIN = "new-player"
        private const val NEW_PASSWORD = "brand-new-password"

        @JvmStatic
        fun invalidTokens(): Stream<Arguments> = Stream.of(
            arguments("issued for another client", GoogleIdTokens.idToken(NEW_SUB, audience = "someone-else")),
            arguments("issued by another issuer", GoogleIdTokens.idToken(NEW_SUB, issuer = "https://evil.example.com")),
            arguments("expired", GoogleIdTokens.idToken(NEW_SUB, expiresAt = Instant.now().minusSeconds(3600))),
            arguments("signed by unknown key", GoogleIdTokens.idToken(NEW_SUB, signedByForeignKey = true)),
            arguments("not a jwt at all", "definitely-not-a-jwt"),
        )

        @JvmStatic
        fun linkedGoogleAccounts(): Stream<Arguments> = Stream.of(
            arguments("google-only account", TestData.GoogleOnly.GOOGLE_SUB, TestData.GoogleOnly.ID),
            arguments("account with password and google", TestData.PasswordAndGoogle.GOOGLE_SUB, TestData.PasswordAndGoogle.ID),
        )

        @JvmStatic
        fun takenLogins(): Stream<Arguments> = Stream.of(
            arguments("login of a password-only account", TestData.PasswordOnly.LOGIN),
            arguments("login of a google-only account", TestData.GoogleOnly.LOGIN),
        )

        @JvmStatic
        fun invalidRegistrations(): Stream<Arguments> = Stream.of(
            arguments("uppercase letters", "New-Player", null),
            arguments("cyrillic", "игрок", null),
            arguments("space inside", "new player", null),
            arguments("too short", "ab", null),
            arguments("too long", "a".repeat(33), null),
            arguments("blank", "", null),
            arguments("too short password", NEW_LOGIN, "short"),
        )
    }
}
