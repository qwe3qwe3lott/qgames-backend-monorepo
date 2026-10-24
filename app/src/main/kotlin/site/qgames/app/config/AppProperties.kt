package site.qgames.app.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.Resource
import java.time.Duration

/** Проверка access-JWT цепочек Security: портал — токены user-auth, админка — токены admin-auth. */
@ConfigurationProperties("qgames.security")
data class SecurityProperties(
    val portal: Jwt,
    val admin: Jwt,
) {
    data class Jwt(
        val issuer: String,
        /** X.509 PEM публичной части ключа подписи модуля (ES256). */
        val publicKey: Resource,
    )
}

/**
 * Фронт и API — поддомены одного домена (same-site), поэтому куки SameSite=Lax доходят и модель CSRF не меняется.
 * Origin перечисляются точно: с allowCredentials wildcard запрещён. У портала и админки — свои списки.
 */
@ConfigurationProperties("qgames.cors")
data class CorsProperties(
    val portal: Origins,
    val admin: Origins,
    val maxAge: Duration = Duration.ofHours(1),
) {
    data class Origins(
        val allowedOrigins: List<String>,
    ) {
        init {
            require(allowedOrigins.none { "*" in it }) { "CORS origins must be listed explicitly, wildcards are not allowed" }
        }
    }
}
