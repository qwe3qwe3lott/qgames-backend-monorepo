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
import site.qgames.userauth.support.TestData.NEW_EMAIL
import java.util.stream.Stream

/**
 * Код подтверждения нельзя положить в фикстуры: он генерируется при регистрации и приходит письмом,
 * поэтому каждый сценарий начинается с регистрации через API.
 */
class EmailVerificationScenariosTest : BaseIntegrationTest() {

    /** Готовит состояние и возвращает пару (email, код), которые будут отправлены на подтверждение. */
    fun interface Setup {
        fun EmailVerificationScenariosTest.prepare(realCode: String): Pair<String, String>
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    fun `email verification scenario`(
        case: String,
        setup: Setup,
        expectedStatus: HttpStatus,
        expectedCode: String?,
        expectedVerified: Boolean,
    ) {
        register(NEW_EMAIL).andExpect { status { isOk() } }
        val (email, code) = with(setup) { prepare(latestVerificationCode(NEW_EMAIL)) }

        val result = verifyEmail(email, code).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            if (expectedCode != null) jsonPath("$.code") { value(expectedCode) }
        }

        if (expectedStatus == HttpStatus.OK) {
            val auth = result.body<AuthResponse>()
            assertThat(auth.user.email).isEqualTo(NEW_EMAIL)
            assertThat(auth.user.emailVerified).isTrue()
            assertThat(auth.tokens.accessToken).isNotBlank()
        }
        assertThat(isVerified(NEW_EMAIL)).isEqualTo(expectedVerified)
    }

    @Test
    fun `wrong attempts are counted and lock the code even for the correct value`() {
        register(NEW_EMAIL).andExpect { status { isOk() } }
        val code = latestVerificationCode(NEW_EMAIL)
        val wrongCode = wrongCodeFor(code)

        repeat(5) { attempt ->
            verifyEmail(NEW_EMAIL, wrongCode).andExpect { status { isBadRequest() } }
            assertThat(activeCodeAttempts()).isEqualTo(attempt + 1)
        }

        verifyEmail(NEW_EMAIL, code).andExpect {
            status { isTooManyRequests() }
            jsonPath("$.code") { value("TOO_MANY_VERIFICATION_ATTEMPTS") }
        }
        assertThat(isVerified(NEW_EMAIL)).isFalse()
    }

    private fun isVerified(email: String): Boolean =
        jdbcClient.sql("""SELECT email_verified FROM "user" WHERE email = :email""")
            .param("email", email)
            .query(Boolean::class.java)
            .single()

    private fun activeCodeAttempts(): Int =
        jdbcClient.sql("SELECT attempts FROM email_verification WHERE consumed_at IS NULL")
            .query(Int::class.java)
            .single()

    companion object {
        private fun wrongCodeFor(code: String) = if (code == "000000") "111111" else "000000"

        @JvmStatic
        fun scenarios(): Stream<Arguments> = Stream.of(
            arguments(
                "correct code verifies email and starts a session",
                Setup { code -> NEW_EMAIL to code },
                HttpStatus.OK, null, true,
            ),
            arguments(
                "email is matched case-insensitively",
                Setup { code -> "Newcomer@Example.com" to code },
                HttpStatus.OK, null, true,
            ),
            arguments(
                "wrong code is rejected",
                Setup { code -> NEW_EMAIL to wrongCodeFor(code) },
                HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_CODE", false,
            ),
            arguments(
                "expired code is rejected",
                Setup { code ->
                    jdbcClient.sql("UPDATE email_verification SET expires_at = now() - interval '1 second'").update()
                    NEW_EMAIL to code
                },
                HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_EXPIRED", false,
            ),
            arguments(
                "code with exhausted attempts is rejected",
                Setup { code ->
                    jdbcClient.sql("UPDATE email_verification SET attempts = 5").update()
                    NEW_EMAIL to code
                },
                HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_VERIFICATION_ATTEMPTS", false,
            ),
            arguments(
                "code superseded by a resent one is rejected",
                Setup { oldCode ->
                    moveVerificationCodeToPast(NEW_EMAIL)
                    resendCode(NEW_EMAIL).andExpect { status { isOk() } }
                    // Совпадение старого и нового кода (1 из миллиона) сделало бы сценарий бессмысленным.
                    check(latestVerificationCode(NEW_EMAIL) != oldCode)
                    NEW_EMAIL to oldCode
                },
                HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_CODE", false,
            ),
            arguments(
                "already verified email cannot be verified again",
                Setup { code ->
                    verifyEmail(NEW_EMAIL, code).andExpect { status { isOk() } }
                    NEW_EMAIL to code
                },
                HttpStatus.CONFLICT, "EMAIL_ALREADY_VERIFIED", true,
            ),
            arguments(
                "unknown email gets the same error as a wrong code",
                Setup { code -> "ghost@example.com" to code },
                HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_CODE", false,
            ),
        )
    }
}
