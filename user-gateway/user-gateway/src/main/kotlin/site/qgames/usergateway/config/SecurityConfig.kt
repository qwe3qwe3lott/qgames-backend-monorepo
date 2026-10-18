package site.qgames.usergateway.config

import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
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
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import site.qgames.usergateway.security.CookieBearerTokenResolver

@Configuration
class SecurityConfig(
    private val properties: GatewayProperties,
) {

    /**
     * CSRF-токены не используются: куки SameSite=Lax не уходят с кросс-сайтовых POST, а API принимает только JSON.
     * Фронт живёт на поддомене того же домена (same-site), поэтому Lax-куки до API доходят.
     * Сессий нет — каждый запрос аутентифицируется по JWT, поэтому любой инстанс gateway обслуживает любой запрос.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "$AUTH_PATH/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/actuator/health/**",
                ).permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer {
                it.bearerTokenResolver(CookieBearerTokenResolver(properties.cookies.accessTokenName, AUTH_PATH))
                it.authenticationEntryPoint(unauthenticatedEntryPoint())
                it.jwt { }
            }
            .exceptionHandling { it.authenticationEntryPoint(unauthenticatedEntryPoint()) }
        return http.build()
    }

    /**
     * CORS только для API: точные origin фронта и allowCredentials, иначе браузер не отправит куки.
     * Запрос с неразрешённого origin отклоняется до аутентификации (403).
     */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val cors = properties.cors
        val configuration = CorsConfiguration().apply {
            allowedOrigins = cors.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf(HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION)
            allowCredentials = true
            maxAge = cors.maxAge.seconds
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/api/**", configuration) }
    }

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwt.jwkSetUri)
            .jwsAlgorithm(SignatureAlgorithm.ES256)
            .build()
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(JwtTimestampValidator(), JwtIssuerValidator(properties.jwt.issuer)),
        )
        return decoder
    }

    private fun unauthenticatedEntryPoint() = AuthenticationEntryPoint { _, response, _ ->
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(
            """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Authentication is required","code":"UNAUTHENTICATED"}""",
        )
    }

    companion object {
        const val AUTH_PATH = "/api/auth"
    }
}
