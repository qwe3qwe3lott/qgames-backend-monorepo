package site.qgames

import com.ninjasquad.springmockk.MockkSpyBean
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import site.qgames.common.test.container.PostgresTestContainer
import site.qgames.common.test.wiremock.WireMockTestServer
import site.qgames.game.repository.session.RoomSessionEventRepository
import site.qgames.game.service.session.SessionStreamService
import site.qgames.game.stream.room.RoomStreamRegistry
import site.qgames.game.stream.session.SessionStreamRegistry
import site.qgames.games.nothing.NothingGameRules
import site.qgames.userauth.support.GoogleIdTokens
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import site.qgames.adminauth.config.CryptoConfig as AdminCryptoConfig
import site.qgames.userauth.config.CryptoConfig as UserCryptoConfig

/**
 * База всех интеграционных тестов приложения: полный контекст (все модули, настоящие цепочки Security), реальная
 * PostgreSQL в контейнере, Google — в WireMock. Перед каждым тестом init/<модуль>.sql пересоздают данные (очистка +
 * фикстуры из TestData модуля). Запросы идут через MockMvc в настоящие контроллеры, состояние проверяется в БД.
 *
 * Контекст один на прогон: все `@MockkSpyBean` объявлены здесь, а базы модулей (UserAuthIntegrationTest,
 * AdminAuthIntegrationTest, GameIntegrationTest) добавляют только хелперы. Пользователь запроса — настоящий
 * access-JWT, подписанный ключом модуля ([userAccessToken], [adminAccessToken]).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
@Sql("/init/app.sql", "/init/user-auth.sql", "/init/admin-auth.sql", "/init/game.sql")
abstract class BaseIntegrationTest {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var jsonMapper: JsonMapper

    @Autowired
    protected lateinit var jdbcClient: JdbcClient

    /** События модулей, опубликованные в потоке теста. */
    @Autowired
    protected lateinit var applicationEvents: ApplicationEvents

    @Autowired
    @Qualifier(UserCryptoConfig.JWT_ENCODER)
    private lateinit var userJwtEncoder: JwtEncoder

    @Autowired
    @Qualifier(AdminCryptoConfig.JWT_ENCODER)
    private lateinit var adminJwtEncoder: JwtEncoder

    @Autowired
    private lateinit var roomStreamRegistry: RoomStreamRegistry

    @Autowired
    private lateinit var sessionStreamRegistry: SessionStreamRegistry

    /** Правила «игры в ничто»: шпион, чтобы проверять ветки, недостижимые с настоящими правилами (сброс — после теста). */
    @MockkSpyBean
    protected lateinit var nothingGameRules: NothingGameRules

    /** Журнал партии: шпион, чтобы проверять, какие события дочитывает сборка состояния. */
    @MockkSpyBean
    protected lateinit var roomSessionEventRepository: RoomSessionEventRepository

    /** Поток партии: шпион, чтобы вклиниваться между регистрацией подписчика и снапшотом (гонки). */
    @MockkSpyBean
    protected lateinit var sessionStreamService: SessionStreamService

    @BeforeEach
    fun resetExternalSystems() {
        WireMockTestServer.server.resetAll()
        GoogleIdTokens.stubJwks(WireMockTestServer.server)
    }

    /** Потоки, открытые тестом, закрываются после него (отключения от комнат — до очистки БД следующего теста). */
    @AfterEach
    fun closeStreams() {
        roomStreamRegistry.closeAll()
        sessionStreamRegistry.closeAll()
    }

    /** Access-JWT игрока, как его выпускает user-auth (ключ, kid и issuer — тестовые настройки модуля). */
    protected fun userAccessToken(subject: String, ttl: Duration = Duration.ofMinutes(15)): String =
        accessToken(userJwtEncoder, "test-key", USER_ISSUER, subject, ttl)

    /** Access-JWT администратора, как его выпускает admin-auth. */
    protected fun adminAccessToken(subject: String, ttl: Duration = Duration.ofMinutes(5)): String =
        accessToken(adminJwtEncoder, "admin-test-key", ADMIN_ISSUER, subject, ttl)

    /** Запрос от имени игрока [userId] (заголовок Authorization: Bearer). */
    protected fun MockHttpServletRequestDsl.asUser(userId: UUID?) {
        userId?.let { header(HttpHeaders.AUTHORIZATION, "Bearer ${userAccessToken(it.toString())}") }
    }

    /** Запрос от имени администратора [adminId]. */
    protected fun MockHttpServletRequestDsl.asAdmin(adminId: UUID?) {
        adminId?.let { header(HttpHeaders.AUTHORIZATION, "Bearer ${adminAccessToken(it.toString())}") }
    }

    protected final inline fun <reified T> ResultActionsDsl.body(): T =
        jsonMapper.readValue(andReturn().response.contentAsString, T::class.java)

    protected fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for: $description")
    }

    /** `SELECT count(*) FROM <sql>`: имя таблицы (в кавычках, с префиксом модуля) и, если нужно, условие. */
    protected fun countRows(sql: String, vararg params: Pair<String, Any?>): Long =
        jdbcClient.sql("SELECT count(*) FROM $sql").also { spec -> params.forEach { spec.param(it.first, it.second) } }
            .query(Long::class.java).single()

    private fun accessToken(encoder: JwtEncoder, keyId: String, issuer: String, subject: String, ttl: Duration): String {
        val now = Instant.now()
        val expiresAt = now.plus(ttl)
        val claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(subject)
            .issuedAt(minOf(now, expiresAt).minusSeconds(60))
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .build()
        val header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keyId).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    companion object {
        const val USER_ISSUER = "qgames-user-auth"
        const val ADMIN_ISSUER = "qgames-admin-auth"

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            PostgresTestContainer.registerProperties(registry)
            registry.add("qgames.auth.google.jwk-set-uri") { WireMockTestServer.baseUrl + GoogleIdTokens.JWKS_PATH }
        }
    }
}
