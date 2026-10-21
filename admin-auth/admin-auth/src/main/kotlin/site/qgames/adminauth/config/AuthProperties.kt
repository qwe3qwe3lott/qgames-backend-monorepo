package site.qgames.adminauth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.Resource
import java.time.Duration

@ConfigurationProperties("qgames.admin-auth")
data class AuthProperties(
    val jwt: Jwt,
    /** Срок refresh-токена. Скользящий: каждая ротация выдаёт токен на полный срок. */
    val refreshTokenTtl: Duration,
    /** Сколько после ротации старый refresh-токен ещё принимается без отзыва сессии (параллельные вкладки). */
    val refreshReuseGrace: Duration,
    val bootstrap: Bootstrap = Bootstrap(),
) {
    data class Jwt(
        val issuer: String,
        val keyId: String,
        /** PKCS#8 PEM приватного ключа ES256. Одинаковый у всех инстансов, берётся из секрета. */
        val privateKey: Resource,
        val publicKey: Resource,
        val accessTokenTtl: Duration,
    )

    /**
     * Первый администратор (env ADMIN_BOOTSTRAP_LOGIN / ADMIN_BOOTSTRAP_PASSWORD). Создаётся при старте, только если
     * администраторов ещё нет. Пустые значения выключают бутстрап.
     */
    data class Bootstrap(
        val login: String? = null,
        val password: String? = null,
    )
}
