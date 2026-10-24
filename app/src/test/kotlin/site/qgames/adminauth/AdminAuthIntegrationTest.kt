package site.qgames.adminauth

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import site.qgames.BaseIntegrationTest
import site.qgames.adminauth.api.cookie.AuthCookieNames
import site.qgames.adminauth.api.dto.admin.AdminResponse
import site.qgames.adminauth.api.dto.auth.LoginRequest
import site.qgames.adminauth.config.CryptoConfig
import site.qgames.adminauth.service.admin.AdminBootstrapService
import site.qgames.adminauth.support.TestData
import java.time.Duration
import java.util.UUID

/**
 * Хелперы тестов модуля admin-auth. Сессия открывается через настоящие ручки `/api/admin/auth/…`: токены приходят в
 * куках (admin_access_token, admin_refresh_token), refresh и logout читают refresh-токен из куки.
 */
abstract class AdminAuthIntegrationTest : BaseIntegrationTest() {

    @Autowired
    @Qualifier(CryptoConfig.JWT_SIGNING_KEY)
    protected lateinit var jwtSigningKey: ECKey

    @Autowired
    @Qualifier(CryptoConfig.PASSWORD_ENCODER)
    protected lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    protected lateinit var adminBootstrapService: AdminBootstrapService

    /** Открытая сессия: администратор из тела ответа и токены из кук. */
    data class AuthSession(val admin: AdminResponse, val tokens: Tokens)

    data class Tokens(val accessToken: String, val refreshToken: String, val refreshTokenMaxAge: Duration)

    protected fun postJson(path: String, body: Any): ResultActionsDsl =
        mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
        }

    protected fun getJson(path: String, adminId: UUID? = null): ResultActionsDsl =
        mockMvc.get(path) { asAdmin(adminId) }

    /** Ответ, открывший сессию: тело — администратор, токены — из Set-Cookie. */
    protected fun ResultActionsDsl.session(): AuthSession {
        val response = andReturn().response
        val cookies = response.getHeaders("Set-Cookie").associateBy { it.substringBefore('=') }
        val refresh = cookies.getValue(AuthCookieNames.REFRESH_TOKEN)
        return AuthSession(
            admin = jsonMapper.readValue(response.contentAsString, AdminResponse::class.java),
            tokens = Tokens(
                accessToken = value(cookies.getValue(AuthCookieNames.ACCESS_TOKEN)),
                refreshToken = value(refresh),
                refreshTokenMaxAge = Duration.ofSeconds(Regex("Max-Age=(\\d+)").find(refresh)!!.groupValues[1].toLong()),
            ),
        )
    }

    private fun value(setCookie: String) = setCookie.substringAfter('=').substringBefore(';')

    /** Проверяет подпись access-токена ключом ES256 и возвращает его claims. */
    protected fun verifiedClaims(accessToken: String, key: ECKey = jwtSigningKey.toPublicJWK()): JWTClaimsSet {
        val jwt = SignedJWT.parse(accessToken)
        check(jwt.verify(ECDSAVerifier(key))) { "Access token signature is invalid" }
        return jwt.jwtClaimsSet
    }

    protected fun login(login: String, password: String = TestData.PASSWORD) =
        postJson("/api/admin/auth/login", LoginRequest(login, password))

    protected fun refresh(refreshToken: String) =
        mockMvc.post("/api/admin/auth/refresh") { cookie(Cookie(AuthCookieNames.REFRESH_TOKEN, refreshToken)) }

    protected fun logout(refreshToken: String) =
        mockMvc.post("/api/admin/auth/logout") { cookie(Cookie(AuthCookieNames.REFRESH_TOKEN, refreshToken)) }

    /** Новая сессия фикстурного администратора. */
    protected fun startSession(adminLogin: String = TestData.Root.LOGIN): AuthSession =
        login(adminLogin).andExpect { status { isOk() } }.session()
}
