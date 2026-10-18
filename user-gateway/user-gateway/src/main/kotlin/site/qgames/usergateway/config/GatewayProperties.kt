package site.qgames.usergateway.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("qgames.gateway")
data class GatewayProperties(
    val jwt: Jwt,
    val cookies: Cookies,
    val cors: Cors,
) {
    /**
     * Фронт и API — поддомены одного домена (same-site), поэтому куки SameSite=Lax доходят и модель CSRF не меняется.
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
        /** JWKS user-auth. Ключи кешируются декодером и перечитываются при неизвестном kid. */
        val jwkSetUri: String,
        val issuer: String,
    )

    data class Cookies(
        val secure: Boolean = true,
        val sameSite: String = "Lax",
        val accessTokenName: String = "access_token",
        val refreshTokenName: String = "refresh_token",
        /** Refresh-кука уходит только на эндпоинты аутентификации. */
        val refreshTokenPath: String = "/api/auth",
    )
}
