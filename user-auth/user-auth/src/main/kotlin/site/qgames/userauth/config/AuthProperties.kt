package site.qgames.userauth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.Resource
import java.time.Duration

@ConfigurationProperties("qgames.auth")
data class AuthProperties(
    val jwt: Jwt,
    val refreshTokenTtl: Duration,
    /** Сколько после ротации старый refresh-токен ещё принимается без отзыва сессии (параллельные вкладки). */
    val refreshReuseGrace: Duration,
    val verification: Verification,
    val mail: Mail,
    val google: Google,
) {
    data class Jwt(
        val issuer: String,
        val keyId: String,
        /** PKCS#8 PEM приватного ключа ES256. Одинаковый у всех инстансов, берётся из секрета. */
        val privateKey: Resource,
        val publicKey: Resource,
        val accessTokenTtl: Duration,
    )

    data class Verification(
        val codeTtl: Duration,
        val maxAttempts: Int,
        val resendCooldown: Duration,
    )

    data class Mail(
        val from: String,
    )

    data class Google(
        val clientIds: List<String> = emptyList(),
        val jwkSetUri: String,
        val issuers: List<String>,
    )
}
