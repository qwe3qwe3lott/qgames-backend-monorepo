package site.qgames.userauth.controller.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.userauth.UserAuthIntegrationTest
import site.qgames.userauth.api.dto.user.ChangePasswordRequest
import site.qgames.userauth.support.TestData
import java.util.UUID
import java.util.stream.Stream

class PasswordChangeScenariosTest : UserAuthIntegrationTest() {

    @Test
    fun `password change ends every session, starts a new one and leaves the user row untouched`() {
        val firstDevice = startSession()
        val secondDevice = startSession()
        val userBefore = userRow(TestData.PasswordOnly.ID)

        val changed = putJson(PASSWORD_PATH, ChangePasswordRequest(TestData.PASSWORD, NEW_PASSWORD), TestData.PasswordOnly.ID)
            .andExpect { status { isOk() } }
            .session()

        assertThat(changed.user.id).isEqualTo(TestData.PasswordOnly.ID)
        assertThat(verifiedClaims(changed.tokens.accessToken).subject).isEqualTo(TestData.PasswordOnly.ID.toString())
        listOf(firstDevice, secondDevice).forEach { refresh(it.tokens.refreshToken).andExpect { status { isUnauthorized() } } }
        refresh(changed.tokens.refreshToken).andExpect { status { isOk() } }

        login(TestData.PasswordOnly.EMAIL, TestData.PASSWORD).andExpect { jsonPath("$.code") { value("INVALID_CREDENTIALS") } }
        login(TestData.PasswordOnly.EMAIL, NEW_PASSWORD).andExpect { status { isOk() } }

        assertThat(userRow(TestData.PasswordOnly.ID)).isEqualTo(userBefore)
        assertThat(publishedProfileEvents()).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedChanges")
    fun `rejected password change keeps the password and sessions`(
        case: String,
        userId: UUID,
        currentPassword: String,
        expectedStatus: Int,
        expectedCode: String,
    ) {
        startSession()
        val identitiesBefore = identityRows()
        val activeSessionsBefore = countRows("\"user_auth#refresh_token\" WHERE revoked_at IS NULL")

        putJson(PASSWORD_PATH, ChangePasswordRequest(currentPassword, NEW_PASSWORD), userId).andExpect {
            status { isEqualTo(expectedStatus) }
            jsonPath("$.code") { value(expectedCode) }
        }

        assertThat(identityRows()).isEqualTo(identitiesBefore)
        assertThat(countRows("\"user_auth#refresh_token\" WHERE revoked_at IS NULL")).isEqualTo(activeSessionsBefore)
    }

    @Test
    fun `too short new password is a validation error`() {
        putJson(PASSWORD_PATH, ChangePasswordRequest(TestData.PASSWORD, "short"), TestData.PasswordOnly.ID).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors.newPassword") { exists() }
        }
    }

    private fun userRow(id: UUID): Map<String, Any?> =
        jdbcClient.sql("""SELECT * FROM "user_auth#user" WHERE id = :id""").param("id", id).query().singleRow()

    private fun identityRows(): List<Map<String, Any?>> =
        jdbcClient.sql("SELECT * FROM \"user_auth#user_identity\" ORDER BY id").query().listOfRows()

    companion object {
        private const val PASSWORD_PATH = "/api/users/me/login-methods/password"
        private const val NEW_PASSWORD = "brand-new-password"

        @JvmStatic
        fun rejectedChanges(): Stream<Arguments> = Stream.of(
            arguments("wrong current password", TestData.PasswordOnly.ID, "wrong-password", 400, "INVALID_CURRENT_PASSWORD"),
            arguments("account without password", TestData.GoogleOnly.ID, TestData.PASSWORD, 409, "PASSWORD_NOT_SET"),
            arguments("unknown user", UUID.randomUUID(), TestData.PASSWORD, 404, "USER_NOT_FOUND"),
        )
    }
}
