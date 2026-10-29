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
    val google: Google,
    val cookies: Cookies = Cookies(),
    val devUsers: DevUsers = DevUsers(),
) {
    data class Jwt(
        val issuer: String,
        val keyId: String,
        /** PKCS#8 PEM приватного ключа ES256, берётся из секрета. */
        val privateKey: Resource,
        val publicKey: Resource,
        val accessTokenTtl: Duration,
    )

    /**
     * Куки сессии (имена и пути — в контракте, [site.qgames.userauth.api.cookie.AuthCookieNames]).
     * Secure и локально: фронт открывается по HTTPS на локальном домене, как в проде (README фронтенда).
     */
    data class Cookies(
        val secure: Boolean = true,
        val sameSite: String = "Lax",
    )

    data class Google(
        val clientIds: List<String> = emptyList(),
        val jwkSetUri: String,
        val issuers: List<String>,
    )

    /**
     * Тестовые игроки локального стенда (задаёт профиль local), см. DevUsersBootstrapService. count = 0 выключает
     * бутстрап.
     */
    data class DevUsers(
        val loginPrefix: String? = null,
        val count: Int = 0,
        val password: String? = null,
    )
}
