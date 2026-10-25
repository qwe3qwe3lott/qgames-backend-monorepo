package site.qgames.app.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.Resource

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
