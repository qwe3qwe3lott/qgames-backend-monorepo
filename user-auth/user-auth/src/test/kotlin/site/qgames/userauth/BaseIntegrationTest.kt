package site.qgames.userauth

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import site.qgames.common.test.container.PostgresTestContainer
import site.qgames.common.test.wiremock.WireMockTestServer
import site.qgames.common.web.header.QgamesHeaders
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.auth.LoginRequest
import site.qgames.userauth.gatewayapi.dto.auth.RefreshTokenRequest
import site.qgames.userauth.asyncapi.event.UserProfileChanged
import site.qgames.userauth.asyncapi.topic.UserAuthTopics
import site.qgames.userauth.support.GoogleIdTokens
import site.qgames.userauth.support.TestData
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * База всех тестов user-auth: полный контекст, реальный PostgreSQL в контейнере, Google — в WireMock.
 * Перед каждым тестом init.sql пересоздаёт данные (очистка + фикстуры из [TestData]).
 * Запросы идут через MockMvc в настоящие контроллеры, состояние проверяется в БД.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql("/init.sql")
abstract class BaseIntegrationTest {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var jsonMapper: JsonMapper

    @Autowired
    protected lateinit var jdbcClient: JdbcClient

    @Autowired
    protected lateinit var jwtSigningKey: ECKey

    @BeforeEach
    fun resetExternalSystems() {
        WireMockTestServer.server.resetAll()
        GoogleIdTokens.stubJwks(WireMockTestServer.server)
    }

    protected fun postJson(path: String, body: Any, userId: UUID? = null): ResultActionsDsl =
        mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
            userId?.let { header(QgamesHeaders.USER_ID, it.toString()) }
        }

    protected fun putJson(path: String, body: Any, userId: UUID? = null): ResultActionsDsl =
        mockMvc.put(path) {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
            userId?.let { header(QgamesHeaders.USER_ID, it.toString()) }
        }

    protected fun getJson(path: String, userId: UUID? = null): ResultActionsDsl =
        mockMvc.get(path) {
            userId?.let { header(QgamesHeaders.USER_ID, it.toString()) }
        }

    protected final inline fun <reified T> ResultActionsDsl.body(): T =
        jsonMapper.readValue(andReturn().response.contentAsString, T::class.java)

    /** Проверяет подпись access-токена ключом ES256 и возвращает его claims. */
    protected fun verifiedClaims(accessToken: String, key: ECKey = jwtSigningKey.toPublicJWK()): JWTClaimsSet {
        val jwt = SignedJWT.parse(accessToken)
        check(jwt.verify(ECDSAVerifier(key))) { "Access token signature is invalid" }
        return jwt.jwtClaimsSet
    }

    /** События профиля, записанные в outbox (в тестах строки не удаляются), от старых к новым. */
    protected fun publishedProfileEvents(): List<UserProfileChanged> =
        jdbcClient.sql(
            "SELECT aggregate_type, aggregate_id, event_type, payload FROM outbox_event ORDER BY created_at, id",
        ).query { rs, _ ->
            check(rs.getString("aggregate_type") == UserAuthTopics.USER_PROFILE_AGGREGATE)
            check(rs.getString("event_type") == UserAuthTopics.USER_PROFILE_CHANGED_EVENT)
            UserProfileChanged.parseFrom(rs.getBytes("payload")).also { check(it.userId == rs.getString("aggregate_id")) }
        }.list()

    protected fun login(email: String, password: String = TestData.PASSWORD) =
        postJson("/api/auth/login", LoginRequest(email, password))

    protected fun loginWithGoogle(idToken: String) =
        postJson("/api/auth/google", GoogleIdTokenRequest(idToken))

    protected fun refresh(refreshToken: String) =
        postJson("/api/auth/refresh", RefreshTokenRequest(refreshToken))

    protected fun logout(refreshToken: String) =
        postJson("/api/auth/logout", RefreshTokenRequest(refreshToken))

    /** Новая сессия фикстуры с паролем. */
    protected fun startSession(email: String = TestData.PasswordOnly.EMAIL): AuthResponse =
        login(email).andExpect { status { isOk() } }.body()

    protected fun countRows(table: String): Long =
        jdbcClient.sql("SELECT count(*) FROM $table").query(Long::class.java).single()

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            PostgresTestContainer.registerProperties(registry)
            registry.add("qgames.auth.google.jwk-set-uri") { WireMockTestServer.baseUrl + GoogleIdTokens.JWKS_PATH }
        }
    }
}
