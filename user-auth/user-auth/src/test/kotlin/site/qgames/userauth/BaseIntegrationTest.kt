package site.qgames.userauth

import com.ninjasquad.springmockk.MockkSpyBean
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
import site.qgames.common.test.container.MailpitTestContainer
import site.qgames.common.test.container.PostgresTestContainer
import site.qgames.common.test.mail.MailpitClient
import site.qgames.common.test.wiremock.WireMockTestServer
import site.qgames.common.web.header.QgamesHeaders
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.gatewayapi.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.gatewayapi.dto.auth.LoginRequest
import site.qgames.userauth.gatewayapi.dto.auth.RefreshTokenRequest
import site.qgames.userauth.gatewayapi.dto.auth.RegisterRequest
import site.qgames.userauth.gatewayapi.dto.auth.ResendCodeRequest
import site.qgames.userauth.gatewayapi.dto.auth.VerifyEmailRequest
import site.qgames.userauth.mail.VerificationMailSender
import site.qgames.userauth.support.GoogleIdTokens
import site.qgames.userauth.support.TestData
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * База всех тестов user-auth: полный контекст, реальные PostgreSQL и Mailpit в контейнерах, Google — в WireMock.
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

    @MockkSpyBean
    protected lateinit var verificationMailSender: VerificationMailSender

    protected val mailpit: MailpitClient
        get() = MailpitTestContainer.client

    @BeforeEach
    fun resetExternalSystems() {
        mailpit.deleteAll()
        WireMockTestServer.server.resetAll()
        GoogleIdTokens.stubJwks(WireMockTestServer.server)
    }

    protected fun postJson(path: String, body: Any, userId: UUID? = null): ResultActionsDsl =
        mockMvc.post(path) {
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

    protected fun register(email: String, password: String = TestData.PASSWORD) =
        postJson("/api/auth/register", RegisterRequest(email, password))

    protected fun verifyEmail(email: String, code: String) =
        postJson("/api/auth/verify-email", VerifyEmailRequest(email, code))

    protected fun resendCode(email: String) =
        postJson("/api/auth/resend-code", ResendCodeRequest(email))

    protected fun login(email: String, password: String = TestData.PASSWORD) =
        postJson("/api/auth/login", LoginRequest(email, password))

    protected fun loginWithGoogle(idToken: String) =
        postJson("/api/auth/google", GoogleIdTokenRequest(idToken))

    protected fun refresh(refreshToken: String) =
        postJson("/api/auth/refresh", RefreshTokenRequest(refreshToken))

    protected fun logout(refreshToken: String) =
        postJson("/api/auth/logout", RefreshTokenRequest(refreshToken))

    /** Новая сессия фикстуры с паролем. */
    protected fun startSession(email: String = TestData.Verified.EMAIL): AuthResponse =
        login(email).andExpect { status { isOk() } }.body()

    /** Последний код подтверждения из реально отправленного письма. */
    protected fun latestVerificationCode(email: String): String {
        repeat(50) {
            val text = mailpit.messagesTo(email).firstOrNull()
            if (text != null) {
                return CODE_REGEX.find(text)?.value ?: error("No verification code in mail: $text")
            }
            Thread.sleep(100)
        }
        error("No verification mail for $email")
    }

    /** Сдвигает время создания активного кода в прошлое, чтобы пройти кулдаун повторной отправки. */
    protected fun moveVerificationCodeToPast(email: String) {
        jdbcClient.sql(
            """
            UPDATE email_verification SET created_at = created_at - interval '1 hour'
            WHERE user_id = (SELECT id FROM "user" WHERE email = :email)
            """,
        ).param("email", email).update()
    }

    protected fun countRows(table: String): Long =
        jdbcClient.sql("SELECT count(*) FROM $table").query(Long::class.java).single()

    companion object {
        private val CODE_REGEX = Regex("\\b\\d{6}\\b")

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            PostgresTestContainer.registerProperties(registry)
            MailpitTestContainer.registerProperties(registry)
            registry.add("qgames.auth.google.jwk-set-uri") { WireMockTestServer.baseUrl + GoogleIdTokens.JWKS_PATH }
        }
    }
}
