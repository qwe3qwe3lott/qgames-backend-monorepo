package site.qgames.userauth.controller.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import site.qgames.userauth.BaseIntegrationTest
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.gatewayapi.dto.user.ChangePasswordRequest
import site.qgames.userauth.support.TestData
import java.util.UUID
import java.util.stream.Stream

class PasswordChangeScenariosTest : BaseIntegrationTest() {

    @Test
    fun `password change ends every session, starts a new one and leaves the user row untouched`() {
        val firstDevice = startSession()
        val secondDevice = startSession()
        val userBefore = userRow(TestData.Verified.ID)

        val changed = putJson(PASSWORD_PATH, ChangePasswordRequest(TestData.PASSWORD, NEW_PASSWORD), TestData.Verified.ID)
            .andExpect { status { isOk() } }
            .body<AuthResponse>()

        assertThat(changed.user.id).isEqualTo(TestData.Verified.ID)
        assertThat(verifiedClaims(changed.tokens.accessToken).subject).isEqualTo(TestData.Verified.ID.toString())
        listOf(firstDevice, secondDevice).forEach { refresh(it.tokens.refreshToken).andExpect { status { isUnauthorized() } } }
        refresh(changed.tokens.refreshToken).andExpect { status { isOk() } }

        login(TestData.Verified.EMAIL, TestData.PASSWORD).andExpect { jsonPath("$.code") { value("INVALID_CREDENTIALS") } }
        login(TestData.Verified.EMAIL, NEW_PASSWORD).andExpect { status { isOk() } }

        assertThat(userRow(TestData.Verified.ID)).isEqualTo(userBefore)
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
        val activeSessionsBefore = countRows("refresh_token WHERE revoked_at IS NULL")

        putJson(PASSWORD_PATH, ChangePasswordRequest(currentPassword, NEW_PASSWORD), userId).andExpect {
            status { isEqualTo(expectedStatus) }
            jsonPath("$.code") { value(expectedCode) }
        }

        assertThat(identityRows()).isEqualTo(identitiesBefore)
        assertThat(countRows("refresh_token WHERE revoked_at IS NULL")).isEqualTo(activeSessionsBefore)
    }

    @Test
    fun `too short new password is a validation error`() {
        putJson(PASSWORD_PATH, ChangePasswordRequest(TestData.PASSWORD, "short"), TestData.Verified.ID).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.errors.newPassword") { exists() }
        }
    }

    private fun userRow(id: UUID): Map<String, Any?> =
        jdbcClient.sql("""SELECT * FROM "user" WHERE id = :id""").param("id", id).query().singleRow()

    private fun identityRows(): List<Map<String, Any?>> =
        jdbcClient.sql("SELECT * FROM user_identity ORDER BY id").query().listOfRows()

    companion object {
        private const val PASSWORD_PATH = "/api/users/me/login-methods/password"
        private const val NEW_PASSWORD = "brand-new-password"

        @JvmStatic
        fun rejectedChanges(): Stream<Arguments> = Stream.of(
            arguments("wrong current password", TestData.Verified.ID, "wrong-password", 400, "INVALID_CURRENT_PASSWORD"),
            arguments("account without password", TestData.GoogleOnly.ID, TestData.PASSWORD, 409, "PASSWORD_NOT_SET"),
            arguments("unknown user", UUID.randomUUID(), TestData.PASSWORD, 404, "USER_NOT_FOUND"),
        )
    }
}
