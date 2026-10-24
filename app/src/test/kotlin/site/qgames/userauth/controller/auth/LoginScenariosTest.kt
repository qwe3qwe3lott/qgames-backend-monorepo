package site.qgames.userauth.controller.auth

import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.get
import site.qgames.userauth.UserAuthIntegrationTest
import site.qgames.userauth.api.dto.user.LoginMethod
import site.qgames.userauth.support.TestData
import site.qgames.userauth.support.TestData.PASSWORD
import site.qgames.userauth.util.Hashing
import java.time.Duration
import java.util.UUID
import java.util.stream.Stream

class LoginScenariosTest : UserAuthIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("credentials")
    fun `password login scenario`(
        case: String,
        email: String,
        password: String,
        expectedStatus: HttpStatus,
        expectedCode: String?,
        expectedUserId: UUID?,
    ) {
        val result = login(email, password).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            if (expectedCode != null) jsonPath("$.code") { value(expectedCode) }
        }

        if (expectedUserId == null) {
            assertThat(countRows("\"user_auth#refresh_token\"")).isZero()
            return
        }
        val auth = result.session()
        assertThat(auth.user.id).isEqualTo(expectedUserId)
        assertThat(auth.user.loginMethods).contains(LoginMethod.PASSWORD)

        val claims = verifiedClaims(auth.tokens.accessToken)
        assertThat(claims.subject).isEqualTo(expectedUserId.toString())
        assertThat(claims.issuer).isEqualTo("qgames-user-auth")
        assertThat(Duration.between(claims.issueTime.toInstant(), claims.expirationTime.toInstant()))
            .isEqualTo(Duration.ofMinutes(15))

        val storedHashes = jdbcClient.sql("SELECT token_hash FROM \"user_auth#refresh_token\"").query(String::class.java).list()
        assertThat(storedHashes)
            .containsExactly(Hashing.sha256Hex(auth.tokens.refreshToken))
            .doesNotContain(auth.tokens.refreshToken)
    }

    @Test
    fun `access token opens the portal and carries the configured key id`() {
        val auth = startSession()

        assertThat(SignedJWT.parse(auth.tokens.accessToken).header.keyID).isEqualTo("test-key")
        getJson("/api/users/me").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/users/me") { header(HttpHeaders.AUTHORIZATION, "Bearer ${auth.tokens.accessToken}") }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(auth.user.id.toString()) }
            }
    }

    companion object {
        @JvmStatic
        fun credentials(): Stream<Arguments> = Stream.of(
            arguments("correct credentials", TestData.PasswordOnly.EMAIL, PASSWORD, HttpStatus.OK, null, TestData.PasswordOnly.ID),
            arguments("email in different case", "PLAYER@example.com", PASSWORD, HttpStatus.OK, null, TestData.PasswordOnly.ID),
            arguments(
                "user with both login methods",
                TestData.PasswordAndGoogle.EMAIL, PASSWORD, HttpStatus.OK, null, TestData.PasswordAndGoogle.ID,
            ),
            arguments("wrong password", TestData.PasswordOnly.EMAIL, "wrong-password", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", null),
            arguments("unknown email looks like wrong password", "ghost@example.com", PASSWORD, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", null),
            arguments(
                "google-only account has no password",
                TestData.GoogleOnly.EMAIL, PASSWORD, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", null,
            ),
        )
    }
}
