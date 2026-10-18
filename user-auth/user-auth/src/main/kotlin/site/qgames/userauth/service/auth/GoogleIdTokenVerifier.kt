package site.qgames.userauth.service.auth

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import site.qgames.userauth.config.AuthProperties
import site.qgames.userauth.exception.auth.AuthErrors

/**
 * Проверяет Google ID token, полученный фронтом через Google Identity Services:
 * подпись по JWKS Google, срок действия, issuer и audience (наш client id).
 */
@Component
class GoogleIdTokenVerifier(properties: AuthProperties) {

    private val google = properties.google

    private val decoder: JwtDecoder = NimbusJwtDecoder.withJwkSetUri(google.jwkSetUri).build().apply {
        setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtTimestampValidator(),
                JwtClaimValidator<Any>(JwtClaimNames.ISS) { it.toString() in google.issuers },
                JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD) { aud -> aud.any { it in google.clientIds } },
            ),
        )
    }

    fun verify(idToken: String): GoogleIdentity {
        val jwt = try {
            decoder.decode(idToken)
        } catch (_: JwtException) {
            throw AuthErrors.invalidGoogleToken()
        }
        val subject = jwt.subject ?: throw AuthErrors.invalidGoogleToken()
        val email = jwt.getClaimAsString("email") ?: throw AuthErrors.invalidGoogleToken()
        return GoogleIdentity(
            subject = subject,
            email = email.lowercase(),
            emailVerified = jwt.getClaimAsString("email_verified").toBoolean(),
        )
    }
}

data class GoogleIdentity(
    val subject: String,
    val email: String,
    val emailVerified: Boolean,
)
