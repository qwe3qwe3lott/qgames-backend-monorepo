package site.qgames.common.test.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

/**
 * Один WireMock на прогон тестов модуля. Стабы сбрасываются в BaseIntegrationTest перед каждым тестом.
 */
object WireMockTestServer {

    val server: WireMockServer = WireMockServer(options().dynamicPort()).apply { start() }

    val baseUrl: String
        get() = server.baseUrl()
}
