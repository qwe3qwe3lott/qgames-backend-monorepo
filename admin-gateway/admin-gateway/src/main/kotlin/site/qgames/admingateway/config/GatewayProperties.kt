package site.qgames.admingateway.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("qgames.gateway")
data class GatewayProperties(
    val jwt: Jwt,
    val cookies: Cookies = Cookies(),
    val cors: Cors,
) {
    /**
     * Админка и API — поддомены одного домена (same-site), поэтому куки SameSite=Lax доходят и модель CSRF не меняется.
     * Origin перечисляются точно: с allowCredentials wildcard запрещён.
     */
    data class Cors(
        val allowedOrigins: List<String>,
        val maxAge: Duration = Duration.ofHours(1),
    ) {
        init {
            require(allowedOrigins.none { "*" in it }) { "CORS origins must be listed explicitly, wildcards are not allowed" }
        }
    }

    data class Jwt(
        /** JWKS admin-auth. Ключи кешируются декодером и перечитываются при неизвестном kid. */
        val jwkSetUri: String,
        val issuer: String,
    )

    /**
     * Имена кук отличаются от кук user-gateway: куки не изолируются по порту, и локально (оба gateway на localhost)
     * одноимённые куки перетёрли бы друг друга.
     */
    data class Cookies(
        val secure: Boolean = true,
        val sameSite: String = "Lax",
        val accessTokenName: String = "admin_access_token",
        val refreshTokenName: String = "admin_refresh_token",
        /** Refresh-кука уходит только на эндпоинты аутентификации. */
        val refreshTokenPath: String = "/api/auth",
    )
}
