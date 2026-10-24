package site.qgames.app.controller.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.get
import site.qgames.BaseIntegrationTest
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.stream.Stream
import site.qgames.adminauth.support.TestData as AdminTestData
import site.qgames.userauth.support.TestData as UserTestData

/**
 * Две цепочки Security: портал принимает только токены user-auth (кука access_token или Authorization), админка
 * (`/api/admin/…`) — только токены admin-auth (кука admin_access_token или Authorization). Пользователь запроса —
 * `sub` проверенного токена.
 */
class AuthenticationScenariosTest : BaseIntegrationTest() {

    fun interface Credentials {
        fun AuthenticationScenariosTest.token(): String?
    }

    fun interface TokenTransport {
        fun MockHttpServletRequestDsl.attach(token: String)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedPortalTokens")
    fun `portal rejects untrusted credentials`(case: String, credentials: Credentials) {
        val token = with(credentials) { token() }
        mockMvc.get("/api/users/me") {
            token?.let { cookie(Cookie("access_token", it)) }
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedAdminTokens")
    fun `admin api rejects untrusted credentials`(case: String, credentials: Credentials) {
        val token = with(credentials) { token() }
        mockMvc.get("/api/admin/admins/me") {
            token?.let { cookie(Cookie("admin_access_token", it)) }
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("portalTransports")
    fun `portal user is taken from the token`(case: String, transport: TokenTransport) {
        mockMvc.get("/api/users/me") {
            with(transport) { attach(userAccessToken(UserTestData.PasswordOnly.ID.toString())) }
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(UserTestData.PasswordOnly.ID.toString()) }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminTransports")
    fun `admin is taken from the admin token`(case: String, transport: TokenTransport) {
        mockMvc.get("/api/admin/admins/me") {
            with(transport) { attach(adminAccessToken(AdminTestData.Root.ID.toString())) }
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(AdminTestData.Root.ID.toString()) }
        }
    }

    /** Заголовок X-User-Id больше ничего не значит: пользователь — только из токена. */
    @Test
    fun `x-user-id header is ignored`() {
        mockMvc.get("/api/users/me") { header("X-User-Id", UserTestData.PasswordOnly.ID.toString()) }
            .andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/users/me") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${userAccessToken(UserTestData.PasswordOnly.ID.toString())}")
            header("X-User-Id", UserTestData.GoogleOnly.ID.toString())
        }.andExpect { jsonPath("$.id") { value(UserTestData.PasswordOnly.ID.toString()) } }
    }

    /** Каталог игр открыт гостям (только GET самих игр); протухшая кука на нём — 401 (Bearer-фильтр проверяет токен и на permitAll). */
    @Test
    fun `game catalog is public, rooms are not`() {
        mockMvc.get("/api/games").andExpect { status { isOk() } }
        mockMvc.get("/api/games/nothing").andExpect { status { isOk() } }
        mockMvc.get("/api/games/unknown").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("GAME_NOT_FOUND") }
        }
        mockMvc.get("/api/rooms/${UUID.randomUUID()}").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/games") {
            cookie(Cookie("access_token", userAccessToken(UserTestData.PasswordOnly.ID.toString(), Duration.ofMinutes(-5))))
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `unknown paths require authentication`() {
        mockMvc.get("/api/unknown").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/admin/unknown").andExpect { status { isUnauthorized() } }
    }

    companion object {
        private val foreignKey = ECKeyGenerator(Curve.P_256).keyID("test-key").generate()

        private fun foreignToken(issuer: String, subject: String): String {
            val claims = JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .issueTime(Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build()
            return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("test-key").build(), claims)
                .apply { sign(ECDSASigner(foreignKey)) }
                .serialize()
        }

        @JvmStatic
        fun rejectedPortalTokens(): Stream<Arguments> = Stream.of(
            arguments("no token", Credentials { null }),
            arguments("not a jwt", Credentials { "garbage" }),
            arguments("expired token", Credentials { userAccessToken(UserTestData.PasswordOnly.ID.toString(), Duration.ofMinutes(-5)) }),
            arguments("token signed by unknown key", Credentials { foreignToken(USER_ISSUER, UserTestData.PasswordOnly.ID.toString()) }),
            arguments("admin token", Credentials { adminAccessToken(UserTestData.PasswordOnly.ID.toString()) }),
            arguments("subject is not a uuid", Credentials { userAccessToken("not-a-uuid") }),
        )

        @JvmStatic
        fun rejectedAdminTokens(): Stream<Arguments> = Stream.of(
            arguments("no token", Credentials { null }),
            arguments("not a jwt", Credentials { "garbage" }),
            arguments("expired token", Credentials { adminAccessToken(AdminTestData.Root.ID.toString(), Duration.ofMinutes(-5)) }),
            arguments("token signed by unknown key", Credentials { foreignToken(ADMIN_ISSUER, AdminTestData.Root.ID.toString()) }),
            arguments("player token", Credentials { userAccessToken(AdminTestData.Root.ID.toString()) }),
        )

        @JvmStatic
        fun portalTransports(): Stream<Arguments> = Stream.of(
            arguments("token in http-only cookie", TokenTransport { cookie(Cookie("access_token", it)) }),
            arguments("token in Authorization header", TokenTransport { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }),
        )

        @JvmStatic
        fun adminTransports(): Stream<Arguments> = Stream.of(
            arguments("token in http-only cookie", TokenTransport { cookie(Cookie("admin_access_token", it)) }),
            arguments("token in Authorization header", TokenTransport { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }),
        )
    }
}
