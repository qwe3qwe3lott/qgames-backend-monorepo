package site.qgames.admingateway.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.web.util.WebUtils

/**
 * Access-токен берётся из HttpOnly-куки, для API-клиентов и Swagger — из заголовка Authorization.
 * На эндпоинтах аутентификации токен не читается: иначе протухшая кука ломала бы /api/auth/refresh.
 */
class CookieBearerTokenResolver(
    private val cookieName: String,
    private val ignoredPathPrefix: String,
) : BearerTokenResolver {

    private val headerResolver = DefaultBearerTokenResolver()

    override fun resolve(request: HttpServletRequest): String? {
        if (request.requestURI.startsWith(ignoredPathPrefix)) {
            return null
        }
        return WebUtils.getCookie(request, cookieName)?.value ?: headerResolver.resolve(request)
    }
}
