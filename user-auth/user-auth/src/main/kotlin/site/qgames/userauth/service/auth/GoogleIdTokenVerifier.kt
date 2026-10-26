package site.qgames.userauth.service.auth

import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.JwtValidationException
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

    init {
        if (google.clientIds.none { it.isNotBlank() }) {
            log.warn("qgames.auth.google.client-ids is empty (GOOGLE_CLIENT_IDS is not set): Google sign-in will reject every token")
        }
    }

    private val decoder: JwtDecoder = NimbusJwtDecoder.withJwkSetUri(google.jwkSetUri).build().apply {
        setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtTimestampValidator(),
                JwtClaimValidator<Any>(JwtClaimNames.ISS) { it.toString() in google.issuers },
                JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD) { aud -> aud.any { it in google.clientIds } },
            ),
        )
    }

    /**
     * В лог при отказе пишется только причина (сообщение валидатора/декодера), но не сам токен и не его claims.
     */
    fun verify(idToken: String): GoogleIdentity {
        val jwt = try {
            decoder.decode(idToken)
        } catch (ex: JwtException) {
            log.warn("Google ID token rejected: {}", reason(ex))
            throw AuthErrors.invalidGoogleToken(ex)
        }
        return GoogleIdentity(subject = jwt.subject ?: rejectMissingClaim(JwtClaimNames.SUB))
    }

    /** У [JwtValidationException] сообщение — только первая ошибка, поэтому перечисляем все. */
    private fun reason(ex: JwtException): String? =
        if (ex is JwtValidationException) {
            ex.errors.joinToString("; ") { it.description ?: it.errorCode }
        } else {
            ex.message
        }

    private fun rejectMissingClaim(claim: String): Nothing {
        log.warn("Google ID token rejected: missing claim '{}'", claim)
        throw AuthErrors.invalidGoogleToken()
    }

    companion object {
        private val log = LoggerFactory.getLogger(GoogleIdTokenVerifier::class.java)
    }
}

/** Нужен только claim `sub`: email не хранится, аккаунт привязывается к Google-аккаунту по нему. */
data class GoogleIdentity(
    val subject: String,
)
