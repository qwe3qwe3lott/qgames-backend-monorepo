package site.qgames.admingateway.controller.auth

import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import site.qgames.admingateway.BaseIntegrationTest
import java.util.stream.Stream

/**
 * Публичный контракт валидируется на gateway: заведомо невалидный запрос не доходит до admin-auth.
 */
class RequestValidationScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLogins")
    fun `invalid login request is rejected by gateway without calling admin-auth`(case: String, body: String, invalidField: String?) {
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            if (invalidField != null) jsonPath("$.errors.$invalidField") { exists() }
        }

        assertNoAdminAuthCalls()
    }

    /** JWKS gateway может запросить для проверки токена — это не вызов сервиса. */
    private fun assertNoAdminAuthCalls() {
        adminAuth.verify(0, anyRequestedFor(urlMatching("/api/.*")))
    }

    companion object {
        private fun login(login: String, password: String) = """{"login":"$login","password":"$password"}"""

        @JvmStatic
        fun invalidLogins(): Stream<Arguments> = Stream.of(
            arguments("empty login", login("", "correct-horse-1"), "login"),
            arguments("blank login", login("   ", "correct-horse-1"), "login"),
            arguments("too long login", login("a".repeat(65), "correct-horse-1"), "login"),
            arguments("empty password", login("root", ""), "password"),
            arguments("too long password", login("root", "p".repeat(129)), "password"),
            arguments("missing login", """{"password":"correct-horse-1"}""", null),
            arguments("missing password", """{"login":"root"}""", null),
            arguments("malformed json", """{"login":""", null),
        )
    }
}
