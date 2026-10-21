package site.qgames.admingateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import site.qgames.adminauth.gatewayapi.dto.admin.AdminResponse
import site.qgames.adminauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.adminauth.gatewayapi.dto.auth.TokenPair
import site.qgames.common.test.wiremock.WireMockTestServer
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * База всех тестов admin-gateway: полный контекст и реальный HTTP до admin-auth, который эмулирует WireMock
 * (включая JWKS). Access-токены подписываются тестовым ключом ES256, опубликованным в этом JWKS.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class BaseIntegrationTest {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var jsonMapper: JsonMapper

    protected val adminAuth: WireMockServer
        get() = WireMockTestServer.server

    @BeforeEach
    fun resetAdminAuth() {
        adminAuth.resetAll()
        adminAuth.stubFor(get(JWKS_PATH).willReturn(okJson(JWKSet(trustedKey.toPublicJWK()).toString())))
    }

    protected fun stubAdminAuthPost(path: String, status: Int, body: Any) {
        adminAuth.stubFor(post(urlEqualTo(path)).willReturn(jsonResponse(status, body)))
    }

    protected fun stubAdminAuthGet(path: String, status: Int, body: Any) {
        adminAuth.stubFor(get(urlEqualTo(path)).willReturn(jsonResponse(status, body)))
    }

    protected fun json(value: Any): String = jsonMapper.writeValueAsString(value)

    /** Заголовок Set-Cookie ответа для куки с данным именем. */
    protected fun ResultActionsDsl.setCookie(name: String): String? =
        andReturn().response.getHeaders(HttpHeaders.SET_COOKIE).singleOrNull { it.startsWith("$name=") }

    private fun jsonResponse(status: Int, body: Any) = aResponse()
        .withStatus(status)
        .withHeader(
            HttpHeaders.CONTENT_TYPE,
            if (status >= 400) MediaType.APPLICATION_PROBLEM_JSON_VALUE else MediaType.APPLICATION_JSON_VALUE,
        )
        .withBody(json(body))

    companion object {
        const val JWKS_PATH = "/.well-known/jwks.json"
        const val ISSUER = "qgames-admin-auth"
        const val ACCESS_COOKIE = "admin_access_token"
        const val REFRESH_COOKIE = "admin_refresh_token"
        val ADMIN_ID: UUID = UUID.fromString("0199f0a0-0000-7000-8000-0000000000a1")

        private val trustedKey: ECKey = ECKeyGenerator(Curve.P_256).keyID("admin-gateway-test-key").generate()
        private val foreignKey: ECKey = ECKeyGenerator(Curve.P_256).keyID("admin-gateway-test-key").generate()

        fun accessToken(
            adminId: UUID = ADMIN_ID,
            expiresAt: Instant = Instant.now().plus(Duration.ofMinutes(5)),
            issuer: String = ISSUER,
            signedByForeignKey: Boolean = false,
        ): String {
            val key = if (signedByForeignKey) foreignKey else trustedKey
            val claims = JWTClaimsSet.Builder()
                .subject(adminId.toString())
                .issuer(issuer)
                .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(5))))
                .expirationTime(Date.from(expiresAt))
                .build()
            val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).build()
            return SignedJWT(header, claims).apply { sign(ECDSASigner(key)) }.serialize()
        }

        fun admin(id: UUID = ADMIN_ID, login: String = "root") =
            AdminResponse(id = id, login = login, createdAt = Instant.parse("2026-10-20T10:00:00Z"))

        fun authResponse(refreshToken: String = "refresh-token-1") = AuthResponse(
            admin = admin(),
            tokens = TokenPair(
                accessToken = accessToken(),
                accessTokenExpiresAt = Instant.now().plus(Duration.ofMinutes(5)),
                refreshToken = refreshToken,
                refreshTokenExpiresAt = Instant.now().plus(Duration.ofMinutes(15)),
            ),
        )

        fun problem(status: Int, code: String) =
            mapOf("type" to "about:blank", "status" to status, "detail" to "error from admin-auth", "code" to code)

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.http.serviceclient.admin-auth.base-url") { WireMockTestServer.baseUrl }
            registry.add("qgames.gateway.jwt.jwk-set-uri") { WireMockTestServer.baseUrl + JWKS_PATH }
        }
    }
}
