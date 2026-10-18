package site.qgames.usergateway.cookie

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.springframework.web.util.WebUtils
import site.qgames.userauth.gatewayapi.dto.auth.TokenPair
import site.qgames.usergateway.config.GatewayProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Единственное место, где токены превращаются в куки. Сервисы за gateway о куках ничего не знают.
 */
@Component
class AuthCookies(
    private val properties: GatewayProperties,
    private val clock: Clock,
) {

    private val cookies get() = properties.cookies

    fun write(response: HttpServletResponse, tokens: TokenPair) {
        add(response, cookies.accessTokenName, tokens.accessToken, "/", maxAgeUntil(tokens.accessTokenExpiresAt))
        add(response, cookies.refreshTokenName, tokens.refreshToken, cookies.refreshTokenPath, maxAgeUntil(tokens.refreshTokenExpiresAt))
    }

    fun clear(response: HttpServletResponse) {
        add(response, cookies.accessTokenName, "", "/", Duration.ZERO)
        add(response, cookies.refreshTokenName, "", cookies.refreshTokenPath, Duration.ZERO)
    }

    fun refreshToken(request: HttpServletRequest): String? =
        WebUtils.getCookie(request, cookies.refreshTokenName)?.value?.takeIf { it.isNotBlank() }

    private fun maxAgeUntil(expiresAt: Instant): Duration =
        Duration.between(clock.instant(), expiresAt).coerceAtLeast(Duration.ZERO)

    private fun add(response: HttpServletResponse, name: String, value: String, path: String, maxAge: Duration) {
        val cookie = ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(cookies.secure)
            .sameSite(cookies.sameSite)
            .path(path)
            .maxAge(maxAge)
            .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }
}
