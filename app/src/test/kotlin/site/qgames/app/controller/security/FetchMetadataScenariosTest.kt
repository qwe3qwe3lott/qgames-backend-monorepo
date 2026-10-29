package site.qgames.app.controller.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import site.qgames.BaseIntegrationTest
import java.util.UUID
import java.util.stream.Stream

/**
 * Межсайтовые запросы к `/api/…` отсекаются по Fetch Metadata: `Sec-Fetch-Site` `cross-site`/`same-site` (соседний
 * поддомен — тот же сайт) и `Sec-Fetch-Mode: navigate` — 403 `CROSS_SITE_REQUEST` до аутентификации. Запросы своего
 * origin и без заголовков проходят.
 */
class FetchMetadataScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}, Sec-Fetch-Site: {1}")
    @MethodSource("crossSiteLogouts")
    fun `cross-site and same-site requests are rejected in both chains`(logoutPath: String, site: String) {
        mockMvc.post(logoutPath) {
            header(SEC_FETCH_SITE, site)
            header(SEC_FETCH_MODE, "no-cors")
        }.andExpectCrossSiteRejected()
    }

    @ParameterizedTest(name = "Sec-Fetch-Site: {0}")
    @MethodSource("navigationSites")
    fun `navigation to the room stream is rejected even from the same origin`(site: String) {
        mockMvc.get("/api/rooms/${UUID.randomUUID()}/events") {
            header(SEC_FETCH_SITE, site)
            header(SEC_FETCH_MODE, "navigate")
        }.andExpectCrossSiteRejected()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("logoutPaths")
    fun `same-origin requests pass`(logoutPath: String) {
        mockMvc.post(logoutPath) {
            header(SEC_FETCH_SITE, "same-origin")
            header(SEC_FETCH_MODE, "cors")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `requests without fetch metadata pass`() {
        mockMvc.get("/api/games").andExpect { status { isOk() } }
    }

    @Test
    fun `paths outside the api are not checked`() {
        mockMvc.get("/v3/api-docs/portal") {
            header(SEC_FETCH_SITE, "cross-site")
            header(SEC_FETCH_MODE, "navigate")
        }.andExpect { status { isOk() } }
    }

    private fun ResultActionsDsl.andExpectCrossSiteRejected() {
        andExpect {
            status { isForbidden() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("CROSS_SITE_REQUEST") }
        }
    }

    companion object {
        private const val SEC_FETCH_SITE = "Sec-Fetch-Site"
        private const val SEC_FETCH_MODE = "Sec-Fetch-Mode"
        private val LOGOUT_PATHS = listOf("/api/auth/logout", "/api/admin/auth/logout")

        @JvmStatic
        fun logoutPaths(): Stream<Arguments> = LOGOUT_PATHS.map { arguments(it) }.stream()

        @JvmStatic
        fun crossSiteLogouts(): Stream<Arguments> =
            LOGOUT_PATHS.flatMap { path -> listOf("cross-site", "same-site").map { arguments(path, it) } }.stream()

        @JvmStatic
        fun navigationSites(): Stream<Arguments> = listOf("cross-site", "same-site", "same-origin", "none").map { arguments(it) }.stream()
    }
}
