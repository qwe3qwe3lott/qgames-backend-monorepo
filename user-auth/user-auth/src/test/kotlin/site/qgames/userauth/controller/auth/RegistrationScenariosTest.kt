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
import site.qgames.userauth.gatewayapi.dto.user.Avatar
import site.qgames.userauth.support.TestData
import site.qgames.userauth.support.TestData.NEW_EMAIL
import site.qgames.userauth.support.TestData.PASSWORD
import site.qgames.userauth.asyncapi.event.Avatar as AvatarProto
import java.util.stream.Stream

class RegistrationScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRegistrations")
    fun `invalid registration is rejected without creating anything`(
        case: String,
        email: String,
        password: String,
        nickname: String,
        invalidField: String,
    ) {
        register(email, password, nickname).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors.$invalidField") { exists() }
        }

        assertThat(countRows("\"user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
        assertThat(countRows("email_verification")).isZero()
        assertThat(publishedProfileEvents()).isEmpty()
    }

    @ParameterizedTest(name = "avatar {0}")
    @ValueSource(strings = ["HORSE", ""])
    fun `unknown or missing avatar is rejected`(avatar: String) {
        val body = mutableMapOf<String, Any?>("email" to NEW_EMAIL, "password" to PASSWORD, "nickname" to "Newcomer")
        if (avatar.isNotEmpty()) body["avatar"] = avatar

        postJson("/api/auth/register", body).andExpect {
            status { isBadRequest() }
        }
        assertThat(countRows("\"user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
    }

    @ParameterizedTest(name = "nickname \"{0}\"")
    @ValueSource(strings = ["nick", "Вася", "player two"])
    fun `nickname with letters of any alphabet and inner spaces is accepted`(nickname: String) {
        register(NEW_EMAIL, nickname = nickname).andExpect { status { isOk() } }

        val stored = jdbcClient.sql("""SELECT nickname FROM "user" WHERE email = :email""")
            .param("email", NEW_EMAIL)
            .query(String::class.java)
            .single()
        assertThat(stored).isEqualTo(nickname)
    }

    @ParameterizedTest(name = "without {0}")
    @ValueSource(strings = ["email", "password", "nickname", "avatar"])
    fun `registration without a required field is rejected`(field: String) {
        val body = mutableMapOf<String, Any?>("email" to NEW_EMAIL, "password" to PASSWORD, "nickname" to "Newcomer", "avatar" to "CAT")
        body.remove(field)

        postJson("/api/auth/register", body).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }
        assertThat(countRows("\"user\"")).isEqualTo(TestData.FIXTURE_USER_COUNT)
    }

    @Test
    fun `email verification without a code is rejected`() {
        postJson("/api/auth/verify-email", mapOf("email" to TestData.Unverified.EMAIL)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `registration publishes the profile snapshot for other services`() {
        val response = register(NEW_EMAIL, nickname = "Lucky Dog", avatar = Avatar.DOG)
            .andExpect { status { isOk() } }
            .body<RegistrationResponse>()

        val event = publishedProfileEvents().single()
        assertThat(event.userId).isEqualTo(response.userId.toString())
        assertThat(event.nickname).isEqualTo("Lucky Dog")
        assertThat(event.avatar).isEqualTo(AvatarProto.AVATAR_DOG)
        assertThat(event.version).isZero()
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
            arguments("malformed email", "not-an-email", PASSWORD, "Newcomer", "email"),
            arguments("blank email", "", PASSWORD, "Newcomer", "email"),
            arguments("too long email", "a".repeat(310) + "@example.com", PASSWORD, "Newcomer", "email"),
            arguments("too short password", NEW_EMAIL, "short", "Newcomer", "password"),
            arguments("too long password", NEW_EMAIL, "p".repeat(129), "Newcomer", "password"),
            arguments("too short nickname", NEW_EMAIL, PASSWORD, "ab", "nickname"),
            arguments("too long nickname", NEW_EMAIL, PASSWORD, "n".repeat(33), "nickname"),
            arguments("nickname with leading space", NEW_EMAIL, PASSWORD, " Bob", "nickname"),
            arguments("blank nickname", NEW_EMAIL, PASSWORD, "   ", "nickname"),
            arguments("nickname of one letter after a space", NEW_EMAIL, PASSWORD, " a", "nickname"),
            arguments("nickname with trailing space", NEW_EMAIL, PASSWORD, "a ", "nickname"),
            arguments("nickname with a control character", NEW_EMAIL, PASSWORD, "a\u0007b", "nickname"),
        )
    }
}
