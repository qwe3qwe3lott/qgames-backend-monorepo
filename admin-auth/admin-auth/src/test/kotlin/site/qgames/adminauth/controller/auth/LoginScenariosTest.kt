package site.qgames.adminauth.controller.auth

import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import site.qgames.adminauth.BaseIntegrationTest
import site.qgames.adminauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.adminauth.support.TestData
import site.qgames.adminauth.support.TestData.PASSWORD
import site.qgames.adminauth.util.Hashing
import java.time.Duration
import java.util.UUID
import java.util.stream.Stream

class LoginScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("credentials")
    fun `login scenario`(
        case: String,
        adminLogin: String,
        password: String,
        expectedStatus: HttpStatus,
        expectedCode: String?,
        expectedAdminId: UUID?,
    ) {
        val result = login(adminLogin, password).andExpect {
            status { isEqualTo(expectedStatus.value()) }
            if (expectedCode != null) jsonPath("$.code") { value(expectedCode) }
        }

        if (expectedAdminId == null) {
            assertThat(countRows("refresh_token")).isZero()
            return
        }
        val auth = result.body<AuthResponse>()
        assertThat(auth.admin.id).isEqualTo(expectedAdminId)
        assertThat(auth.admin.login).isEqualTo(adminLogin)

        val claims = verifiedClaims(auth.tokens.accessToken)
        assertThat(claims.subject).isEqualTo(expectedAdminId.toString())
        assertThat(claims.issuer).isEqualTo("qgames-admin-auth")
        assertThat(Duration.between(claims.issueTime.toInstant(), claims.expirationTime.toInstant()))
            .isEqualTo(Duration.ofMinutes(5))
        assertThat(Duration.between(claims.issueTime.toInstant(), auth.tokens.refreshTokenExpiresAt))
            .isBetween(Duration.ofMinutes(15), Duration.ofMinutes(15).plusSeconds(1))

        val storedHashes = jdbcClient.sql("SELECT token_hash FROM refresh_token").query(String::class.java).list()
        assertThat(storedHashes)
            .containsExactly(Hashing.sha256Hex(auth.tokens.refreshToken))
            .doesNotContain(auth.tokens.refreshToken)
    }

    @Test
    fun `access token is verifiable by anyone holding only the published JWKS`() {
        val auth = startSession()

        val jwksJson = getJson("/.well-known/jwks.json").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val publishedKey = JWKSet.parse(jwksJson).getKeyByKeyId("admin-test-key") as ECKey

        assertThat(publishedKey.isPrivate).isFalse()
        assertThat(verifiedClaims(auth.tokens.accessToken, publishedKey).subject).isEqualTo(auth.admin.id.toString())
        assertThat(SignedJWT.parse(auth.tokens.accessToken).header.keyID).isEqualTo("admin-test-key")
    }

    companion object {
        @JvmStatic
        fun credentials(): Stream<Arguments> = Stream.of(
            arguments("correct credentials", TestData.Root.LOGIN, PASSWORD, HttpStatus.OK, null, TestData.Root.ID),
            arguments("another admin", TestData.Operator.LOGIN, PASSWORD, HttpStatus.OK, null, TestData.Operator.ID),
            arguments("wrong password", TestData.Root.LOGIN, "wrong-password", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", null),
            arguments("unknown login looks like wrong password", "ghost", PASSWORD, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", null),
            arguments("login in different case", "ROOT", PASSWORD, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", null),
            arguments("login with surrounding spaces is not trimmed", " root ", PASSWORD, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", null),
            arguments("blank login", " ", PASSWORD, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", null),
            arguments("too long login", "a".repeat(65), PASSWORD, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", null),
            arguments("blank password", TestData.Root.LOGIN, "", HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", null),
            arguments("too long password", TestData.Root.LOGIN, "p".repeat(129), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", null),
        )
    }
}
