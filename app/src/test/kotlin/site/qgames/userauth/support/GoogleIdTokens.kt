package site.qgames.userauth.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Эмулятор Google: свой RSA-ключ, JWKS в WireMock и выпуск ID token с произвольными claims.
 */
object GoogleIdTokens {

    const val CLIENT_ID = "test-google-client-id"
    const val ISSUER = "https://accounts.google.com"
    const val JWKS_PATH = "/google/oauth2/v3/certs"

    private val googleKey: RSAKey = RSAKeyGenerator(2048).keyID("google-test-key").generate()
    private val foreignKey: RSAKey = RSAKeyGenerator(2048).keyID("google-test-key").generate()

    fun stubJwks(server: WireMockServer) {
        server.stubFor(get(JWKS_PATH).willReturn(okJson(JWKSet(googleKey.toPublicJWK()).toString())))
    }

    fun idToken(
        subject: String,
        audience: String = CLIENT_ID,
        issuer: String = ISSUER,
        expiresAt: Instant = Instant.now().plus(Duration.ofHours(1)),
        signedByForeignKey: Boolean = false,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(issuer)
            .audience(audience)
            .issueTime(Date.from(expiresAt.minus(Duration.ofHours(1))))
            .expirationTime(Date.from(expiresAt))
            .build()
        val key = if (signedByForeignKey) foreignKey else googleKey
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).type(JOSEObjectType.JWT).build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
    }
}
