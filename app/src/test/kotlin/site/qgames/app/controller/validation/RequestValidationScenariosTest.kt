package site.qgames.app.controller.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request
import site.qgames.BaseIntegrationTest
import site.qgames.userauth.api.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.api.dto.auth.LoginRequest
import site.qgames.userauth.api.dto.user.ChangePasswordRequest
import site.qgames.userauth.api.dto.user.SetPasswordRequest
import site.qgames.userauth.api.dto.user.UpdateProfileRequest
import site.qgames.userauth.support.TestData
import java.util.UUID
import java.util.stream.Stream
import site.qgames.userauth.table.user.UserTable
import site.qgames.userauth.table.user.UserIdentityTable
import site.qgames.userauth.table.auth.RefreshTokenTable as UserRefreshTokenTable
import site.qgames.adminauth.table.admin.AdminTable
import site.qgames.adminauth.table.auth.RefreshTokenTable as AdminRefreshTokenTable

/**
 * Публичные запросы портала и админки валидируются в контроллерах модулей: заведомо невалидный запрос получает
 * 400 VALIDATION_ERROR (с полем в `errors`, если тело разобралось) и ничего не меняет в БД.
 */
class RequestValidationScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    fun `invalid request is rejected and changes nothing`(
        case: String,
        method: HttpMethod,
        path: String,
        body: String,
        userId: UUID?,
        invalidField: String?,
    ) {
        val before = snapshot()

        mockMvc.request(method, path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
            asUser(userId)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            if (invalidField != null) jsonPath("$.errors.$invalidField") { exists() }
        }

        assertThat(snapshot()).isEqualTo(before)
    }

    /** Состояние таблиц, которые могли бы измениться: пользователи, способы входа, сессии, администраторы. */
    private fun snapshot(): List<Any?> = listOf(
        snapshot(UserTable),
        snapshot(UserIdentityTable),
        count(UserRefreshTokenTable),
        snapshot(AdminTable),
        count(AdminRefreshTokenTable),
    )

    companion object {
        private const val PROFILE = "/api/users/me/profile"
        private const val PASSWORD = "/api/users/me/login-methods/password"
        private const val GOOGLE = "/api/users/me/login-methods/google"
        private val PLAYER = TestData.PasswordOnly.ID
        private val GOOGLE_ONLY = TestData.GoogleOnly.ID

        private fun json(body: Any) = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body)

        @JvmStatic
        fun invalidRequests(): Stream<Arguments> = Stream.of(
            arguments("login with malformed email", HttpMethod.POST, "/api/auth/login", json(LoginRequest("not-an-email", "correct-horse-1")), null, "email"),
            arguments("login with blank password", HttpMethod.POST, "/api/auth/login", json(LoginRequest(TestData.PasswordOnly.EMAIL, "")), null, "password"),
            arguments("login without password", HttpMethod.POST, "/api/auth/login", """{"email": "player@example.com"}""", null, null),
            arguments("login without email", HttpMethod.POST, "/api/auth/login", """{"password": "correct-horse-1"}""", null, null),
            arguments("google login with blank token", HttpMethod.POST, "/api/auth/google", json(GoogleIdTokenRequest("")), null, "idToken"),
            arguments("new password too short", HttpMethod.POST, PASSWORD, json(SetPasswordRequest("short")), GOOGLE_ONLY, "password"),
            arguments("set password without password", HttpMethod.POST, PASSWORD, "{}", GOOGLE_ONLY, null),
            arguments("changed password too short", HttpMethod.PUT, PASSWORD, json(ChangePasswordRequest(TestData.PASSWORD, "short")), PLAYER, "newPassword"),
            arguments(
                "password change with blank current password", HttpMethod.PUT, PASSWORD,
                json(ChangePasswordRequest("", "brand-new-password")), PLAYER, "currentPassword",
            ),
            arguments("google link with blank token", HttpMethod.POST, GOOGLE, json(GoogleIdTokenRequest("")), PLAYER, "idToken"),
            arguments("nickname with spaces around", HttpMethod.PATCH, PROFILE, json(UpdateProfileRequest(nickname = " Bob ")), PLAYER, "nickname"),
            arguments("nickname with leading space", HttpMethod.PATCH, PROFILE, json(UpdateProfileRequest(nickname = " ab")), PLAYER, "nickname"),
            arguments("nickname with control character", HttpMethod.PATCH, PROFILE, json(UpdateProfileRequest(nickname = "a\u0007bc")), PLAYER, "nickname"),
            arguments("too long nickname", HttpMethod.PATCH, PROFILE, json(UpdateProfileRequest(nickname = "n".repeat(33))), PLAYER, "nickname"),
            arguments("unknown avatar", HttpMethod.PATCH, PROFILE, """{"avatar": "HORSE"}""", PLAYER, null),
            arguments("admin login without login", HttpMethod.POST, "/api/admin/auth/login", """{"password": "correct-horse-1"}""", null, null),
            arguments("admin login without password", HttpMethod.POST, "/api/admin/auth/login", """{"login": "root"}""", null, null),
            arguments("admin login with blank login", HttpMethod.POST, "/api/admin/auth/login", """{"login": " ", "password": "correct-horse-1"}""", null, "login"),
            arguments("admin login with malformed json", HttpMethod.POST, "/api/admin/auth/login", """{"login": "root", """, null, null),
            arguments("portal login with malformed json", HttpMethod.POST, "/api/auth/login", "not json", null, null),
        )
    }
}
