package site.qgames.userauth

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import site.qgames.BaseIntegrationTest
import site.qgames.userauth.api.cookie.AuthCookieNames
import site.qgames.userauth.api.dto.auth.GoogleIdTokenRequest
import site.qgames.userauth.api.dto.auth.LoginRequest
import site.qgames.userauth.api.dto.user.UserResponse
import site.qgames.userauth.api.event.user.UserProfileChanged
import site.qgames.userauth.config.CryptoConfig
import site.qgames.userauth.support.TestData
import java.util.UUID

/**
 * Хелперы тестов модуля user-auth. Сессия открывается через настоящие ручки: токены приходят в куках
 * (access_token, refresh_token), refresh и logout читают refresh-токен из куки.
 */
abstract class UserAuthIntegrationTest : BaseIntegrationTest() {

    @Autowired
    @Qualifier(CryptoConfig.JWT_SIGNING_KEY)
    protected lateinit var jwtSigningKey: ECKey

    /** Открытая сессия: пользователь из тела ответа и токены из кук. */
    data class AuthSession(val user: UserResponse, val tokens: Tokens)

    data class Tokens(val accessToken: String, val refreshToken: String)

    protected fun postJson(path: String, body: Any, userId: UUID? = null): ResultActionsDsl =
        mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
            asUser(userId)
        }

    protected fun putJson(path: String, body: Any, userId: UUID? = null): ResultActionsDsl =
        mockMvc.put(path) {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
            asUser(userId)
        }

    protected fun getJson(path: String, userId: UUID? = null): ResultActionsDsl =
        mockMvc.get(path) { asUser(userId) }

    /** Ответ, открывший сессию: тело — пользователь, токены — из Set-Cookie. */
    protected fun ResultActionsDsl.session(): AuthSession {
        val response = andReturn().response
        val cookies = response.getHeaders("Set-Cookie").associate { it.substringBefore('=') to it.substringAfter('=').substringBefore(';') }
        return AuthSession(
            user = jsonMapper.readValue(response.contentAsString, UserResponse::class.java),
            tokens = Tokens(
                accessToken = cookies.getValue(AuthCookieNames.ACCESS_TOKEN),
                refreshToken = cookies.getValue(AuthCookieNames.REFRESH_TOKEN),
            ),
        )
    }

    /** Проверяет подпись access-токена ключом ES256 и возвращает его claims. */
    protected fun verifiedClaims(accessToken: String, key: ECKey = jwtSigningKey.toPublicJWK()): JWTClaimsSet {
        val jwt = SignedJWT.parse(accessToken)
        check(jwt.verify(ECDSAVerifier(key))) { "Access token signature is invalid" }
        return jwt.jwtClaimsSet
    }

    /** События профиля, опубликованные модулем user-auth в этом тесте, по порядку. */
    protected fun publishedProfileEvents(): List<UserProfileChanged> =
        applicationEvents.stream(UserProfileChanged::class.java).toList()

    protected fun login(email: String, password: String = TestData.PASSWORD) =
        postJson("/api/auth/login", LoginRequest(email, password))

    protected fun loginWithGoogle(idToken: String) =
        postJson("/api/auth/google", GoogleIdTokenRequest(idToken))

    protected fun refresh(refreshToken: String) =
        mockMvc.post("/api/auth/refresh") { cookie(Cookie(AuthCookieNames.REFRESH_TOKEN, refreshToken)) }

    protected fun logout(refreshToken: String) =
        mockMvc.post("/api/auth/logout") { cookie(Cookie(AuthCookieNames.REFRESH_TOKEN, refreshToken)) }

    /** Новая сессия фикстуры с паролем. */
    protected fun startSession(email: String = TestData.PasswordOnly.EMAIL): AuthSession =
        login(email).andExpect { status { isOk() } }.session()
}
