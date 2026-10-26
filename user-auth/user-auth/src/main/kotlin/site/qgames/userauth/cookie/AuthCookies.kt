package site.qgames.userauth.cookie

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.springframework.web.util.WebUtils
import site.qgames.userauth.api.cookie.AuthCookieNames
import site.qgames.userauth.config.AuthProperties
import site.qgames.userauth.model.auth.TokenPairModel
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Единственное место, где токены игрока превращаются в куки: в теле ответов токенов нет.
 * Access — `Path=/`, refresh — только на эндпоинты аутентификации.
 */
@Component
class AuthCookies(
    private val properties: AuthProperties,
    private val clock: Clock,
) {

    private val cookies get() = properties.cookies

    fun write(response: HttpServletResponse, tokens: TokenPairModel) {
        add(response, AuthCookieNames.ACCESS_TOKEN, tokens.accessToken, AuthCookieNames.ACCESS_TOKEN_PATH, maxAgeUntil(tokens.accessTokenExpiresAt))
        add(response, AuthCookieNames.REFRESH_TOKEN, tokens.refreshToken, AuthCookieNames.AUTH_PATH, maxAgeUntil(tokens.refreshTokenExpiresAt))
    }

    fun clear(response: HttpServletResponse) {
        add(response, AuthCookieNames.ACCESS_TOKEN, "", AuthCookieNames.ACCESS_TOKEN_PATH, Duration.ZERO)
        add(response, AuthCookieNames.REFRESH_TOKEN, "", AuthCookieNames.AUTH_PATH, Duration.ZERO)
    }

    fun refreshToken(request: HttpServletRequest): String? =
        WebUtils.getCookie(request, AuthCookieNames.REFRESH_TOKEN)?.value?.takeIf { it.isNotBlank() }

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
