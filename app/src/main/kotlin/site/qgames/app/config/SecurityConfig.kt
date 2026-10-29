package site.qgames.app.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.proc.SingleKeyJWSKeySelector
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.io.Resource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.SecurityContextHolderFilter
import site.qgames.app.security.CookieBearerTokenResolver
import site.qgames.app.security.FetchMetadataFilter
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import site.qgames.adminauth.api.cookie.AuthCookieNames as AdminCookies
import site.qgames.userauth.api.cookie.AuthCookieNames as PortalCookies

/**
 * Две цепочки Security: админка (`/api/admin/…`, кука admin_access_token, токены admin-auth) и портал (всё остальное,
 * кука access_token, токены user-auth). У каждой свой ключ проверки и issuer: токен игрока в админку не проходит,
 * токен админа на портал — тоже. Пользователь запроса для модулей — `sub` токена (RequestUserContext).
 *
 * Фронты ходят в API своего origin (reverse proxy отдаёт статику и `/api` с одного хоста), поэтому CORS не нужен и
 * выключен в обеих цепочках явно: заголовков CORS нет. С чужого origin браузер не отправит JSON-запрос (нужен
 * preflight, а он не проходит); простые запросы с соседнего поддомена и чужих сайтов (POST без тела, переход по ссылке)
 * отсекает [FetchMetadataFilter] — он стоит первым в обеих цепочках, до аутентификации.
 *
 * CSRF-токены не используются: куки SameSite=Lax не уходят с кросс-сайтовых POST, а тела запросов — только JSON.
 * Сессий нет — каждый запрос аутентифицируется по JWT.
 */
@Configuration
class SecurityConfig(
    private val securityProperties: SecurityProperties,
) {

    @Bean
    @Order(1)
    fun adminSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("${AdminCookies.ADMIN_PATH}/**")
            .cors { it.disable() }
            .csrf { it.disable() }
            .addFilterBefore(FetchMetadataFilter(), SecurityContextHolderFilter::class.java)
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("${AdminCookies.AUTH_PATH}/**").permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer {
                it.bearerTokenResolver(CookieBearerTokenResolver(AdminCookies.ACCESS_TOKEN, AdminCookies.AUTH_PATH))
                it.authenticationEntryPoint(unauthenticatedEntryPoint())
                it.jwt { jwt -> jwt.decoder(adminJwtDecoder()) }
            }
            .exceptionHandling { it.authenticationEntryPoint(unauthenticatedEntryPoint()) }
        return http.build()
    }

    @Bean
    @Order(2)
    fun portalSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.disable() }
            .csrf { it.disable() }
            .addFilterBefore(FetchMetadataFilter(), SecurityContextHolderFilter::class.java)
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "${PortalCookies.AUTH_PATH}/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/actuator/health/**",
                ).permitAll()
                // Каталог игр и параметры игры видны и гостям. Только GET и только сами игры, не их комнаты.
                it.requestMatchers(HttpMethod.GET, GAMES_PATH, "$GAMES_PATH/*").permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer {
                it.bearerTokenResolver(CookieBearerTokenResolver(PortalCookies.ACCESS_TOKEN, PortalCookies.AUTH_PATH))
                it.authenticationEntryPoint(unauthenticatedEntryPoint())
                it.jwt { jwt -> jwt.decoder(portalJwtDecoder()) }
            }
            .exceptionHandling { it.authenticationEntryPoint(unauthenticatedEntryPoint()) }
        return http.build()
    }

    @Bean
    fun portalJwtDecoder(): JwtDecoder = jwtDecoder(securityProperties.portal)

    @Bean
    fun adminJwtDecoder(): JwtDecoder = jwtDecoder(securityProperties.admin)

    /**
     * Проверка подписи публичным ключом модуля из конфигурации (без JWKS-эндпоинта), срока действия и issuer.
     * `withPublicKey` в Spring Security принимает только RSA, поэтому EC-ключ задаётся селектором одного ключа:
     * подпись проверяется им независимо от kid токена (kid — настройка модуля-подписанта).
     */
    private fun jwtDecoder(jwt: SecurityProperties.Jwt): JwtDecoder {
        val publicKey = readPublicKey(jwt.publicKey)
        val decoder = NimbusJwtDecoder.withJwkSource(ImmutableJWKSet<SecurityContext>(JWKSet(ECKey.Builder(Curve.P_256, publicKey).build())))
            .jwsAlgorithm(SignatureAlgorithm.ES256)
            .jwtProcessorCustomizer { it.jwsKeySelector = SingleKeyJWSKeySelector(JWSAlgorithm.ES256, publicKey) }
            .build()
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(JwtTimestampValidator(), JwtIssuerValidator(jwt.issuer)))
        return decoder
    }

    private fun readPublicKey(resource: Resource): ECPublicKey {
        val body = resource.inputStream.use { it.readAllBytes().decodeToString() }
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(body))) as ECPublicKey
    }

    private fun unauthenticatedEntryPoint() = AuthenticationEntryPoint { _, response, _ ->
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(
            """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Authentication is required","code":"UNAUTHENTICATED"}""",
        )
    }

    companion object {
        const val GAMES_PATH = "/api/games"
    }
}
