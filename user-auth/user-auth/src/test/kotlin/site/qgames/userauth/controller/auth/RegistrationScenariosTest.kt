package site.qgames.userauth.controller.auth

import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mail.MailSendException
import site.qgames.userauth.BaseIntegrationTest
import site.qgames.userauth.gatewayapi.dto.auth.RegistrationResponse
import site.qgames.userauth.support.TestData
import site.qgames.userauth.support.TestData.NEW_EMAIL
import site.qgames.userauth.support.TestData.PASSWORD
import java.util.stream.Stream

class RegistrationScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRegistrations")
    fun `invalid registration is rejected without creating anything`(
        case: String,
        email: String,
        password: String,
        invalidField: String,
    ) {
        register(email, password).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors.$invalidField") { exists() }
        }

        assertThat(countRows("\"user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
        assertThat(countRows("email_verification")).isZero()
    }

    @ParameterizedTest(name = "raw email \"{0}\"")
    @ValueSource(strings = [NEW_EMAIL, "Newcomer@Example.COM"])
    fun `registration normalizes email, stores argon2 hash and mails a hashed one-time code`(rawEmail: String) {
        val response = register(rawEmail).andExpect { status { isOk() } }.body<RegistrationResponse>()

        assertThat(response.email).isEqualTo(NEW_EMAIL)
        val stored = jdbcClient.sql(
            """
            SELECT u.id, u.email_verified, i.provider, i.subject, i.password_hash
            FROM "user" u JOIN user_identity i ON i.user_id = u.id
            WHERE u.email = :email
            """,
        ).param("email", NEW_EMAIL).query().singleRow()
        assertThat(stored["id"]).isEqualTo(response.userId)
        assertThat(stored["email_verified"]).isEqualTo(false)
        assertThat(stored["provider"]).isEqualTo("PASSWORD")
        assertThat(stored["subject"]).isEqualTo(NEW_EMAIL)
        assertThat(stored["password_hash"] as String).startsWith("\$argon2id\$").doesNotContain(PASSWORD)

        val code = latestVerificationCode(NEW_EMAIL)
        val storedCodeHash = jdbcClient.sql("SELECT code_hash FROM email_verification WHERE user_id = :id")
            .param("id", response.userId)
            .query(String::class.java)
            .single()
        assertThat(storedCodeHash).hasSize(64).doesNotContain(code)
    }

    @ParameterizedTest(name = "registration as \"{0}\"")
    @ValueSource(strings = [TestData.Verified.EMAIL, "PLAYER@EXAMPLE.COM", TestData.GoogleOnly.EMAIL])
    fun `already registered email cannot be registered again regardless of case or login method`(email: String) {
        register(email, "another-password-2").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("EMAIL_ALREADY_REGISTERED") }
        }
        assertThat(countRows("\"user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
    }

    @Test
    fun `newly registered user can log in only after verifying email`() {
        register(NEW_EMAIL).andExpect { status { isOk() } }

        login(NEW_EMAIL).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("EMAIL_NOT_VERIFIED") }
        }

        verifyEmail(NEW_EMAIL, latestVerificationCode(NEW_EMAIL)).andExpect { status { isOk() } }
        login(NEW_EMAIL).andExpect { status { isOk() } }
    }

    @Test
    fun `registration survives mail outage and code can be resent after cooldown`() {
        every { verificationMailSender.send(any(), any()) } throws MailSendException("SMTP is down")

        register(NEW_EMAIL).andExpect { status { isOk() } }
        assertThat(countRows("\"user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT + 1)
        assertThat(mailpit.messagesTo(NEW_EMAIL)).isEmpty()

        every { verificationMailSender.send(any(), any()) } answers { callOriginal() }
        resendCode(NEW_EMAIL).andExpect {
            status { isTooManyRequests() }
            jsonPath("$.code") { value("RESEND_COOLDOWN") }
        }

        moveVerificationCodeToPast(NEW_EMAIL)
        resendCode(NEW_EMAIL).andExpect { status { isOk() } }
        verifyEmail(NEW_EMAIL, latestVerificationCode(NEW_EMAIL)).andExpect { status { isOk() } }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["unknown@example.com", TestData.Verified.EMAIL])
    fun `resend sends nothing for unknown or already verified email`(email: String) {
        resendCode(email).andExpect { status { isOk() } }

        assertThat(mailpit.messagesTo(email)).isEmpty()
        assertThat(countRows("email_verification")).isZero()
    }

    companion object {
        @JvmStatic
        fun invalidRegistrations(): Stream<Arguments> = Stream.of(
            arguments("malformed email", "not-an-email", PASSWORD, "email"),
            arguments("blank email", "", PASSWORD, "email"),
            arguments("too long email", "a".repeat(310) + "@example.com", PASSWORD, "email"),
            arguments("too short password", NEW_EMAIL, "short", "password"),
            arguments("too long password", NEW_EMAIL, "p".repeat(129), "password"),
        )
    }
}
