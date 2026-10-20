package site.qgames.adminauth

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import site.qgames.adminauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.adminauth.gatewayapi.dto.auth.LoginRequest
import site.qgames.adminauth.gatewayapi.dto.auth.RefreshTokenRequest
import site.qgames.adminauth.service.admin.AdminBootstrapService
import site.qgames.adminauth.support.TestData
import site.qgames.common.test.container.PostgresTestContainer
import site.qgames.common.web.header.QgamesHeaders
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * База всех тестов admin-auth: полный контекст и реальная PostgreSQL в контейнере.
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

    @Autowired
    protected lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    protected lateinit var adminBootstrapService: AdminBootstrapService

    protected fun postJson(path: String, body: Any): ResultActionsDsl =
        mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
        }

    protected fun getJson(path: String, adminId: UUID? = null): ResultActionsDsl =
        mockMvc.get(path) {
            adminId?.let { header(QgamesHeaders.USER_ID, it.toString()) }
        }

    protected final inline fun <reified T> ResultActionsDsl.body(): T =
        jsonMapper.readValue(andReturn().response.contentAsString, T::class.java)

    /** Проверяет подпись access-токена ключом ES256 и возвращает его claims. */
    protected fun verifiedClaims(accessToken: String, key: ECKey = jwtSigningKey.toPublicJWK()): JWTClaimsSet {
        val jwt = SignedJWT.parse(accessToken)
        check(jwt.verify(ECDSAVerifier(key))) { "Access token signature is invalid" }
        return jwt.jwtClaimsSet
    }

    protected fun login(login: String, password: String = TestData.PASSWORD) =
        postJson("/api/auth/login", LoginRequest(login, password))

    protected fun refresh(refreshToken: String) =
        postJson("/api/auth/refresh", RefreshTokenRequest(refreshToken))

    protected fun logout(refreshToken: String) =
        postJson("/api/auth/logout", RefreshTokenRequest(refreshToken))

    /** Новая сессия фикстурного администратора. */
    protected fun startSession(adminLogin: String = TestData.Root.LOGIN): AuthResponse =
        login(adminLogin).andExpect { status { isOk() } }.body()

    protected fun countRows(table: String): Long =
        jdbcClient.sql("SELECT count(*) FROM $table").query(Long::class.java).single()

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            PostgresTestContainer.registerProperties(registry)
        }
    }
}
