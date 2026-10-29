package site.qgames.app.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Отсекает межсайтовые запросы к API по заголовкам Fetch Metadata. Портал и админка — один сайт (поддомены публичного
 * суффикса), поэтому Lax-куки портала уходят и со страницы админки; а поток комнаты (GET, который входит в комнату)
 * открывается переходом верхнего уровня с любого сайта. Наши фронты ходят в API только своего origin.
 *
 * На `/api/…` — 403 `CROSS_SITE_REQUEST`, если `Sec-Fetch-Site` равен `cross-site` или `same-site` либо
 * `Sec-Fetch-Mode` равен `navigate`. Запросы без этих заголовков (curl, старые браузеры) пропускаются.
 */
class FetchMetadataFilter : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith(API_PATH)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (isCrossSite(request)) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            response.writer.write(
                """{"type":"about:blank","title":"Forbidden","status":403,"detail":"Cross-site requests are not allowed","code":"CROSS_SITE_REQUEST"}""",
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun isCrossSite(request: HttpServletRequest): Boolean =
        request.getHeader(SEC_FETCH_SITE) in REJECTED_SITES || request.getHeader(SEC_FETCH_MODE) == NAVIGATE

    private companion object {
        const val API_PATH = "/api/"
        const val SEC_FETCH_SITE = "Sec-Fetch-Site"
        const val SEC_FETCH_MODE = "Sec-Fetch-Mode"
        const val NAVIGATE = "navigate"
        val REJECTED_SITES = setOf("cross-site", "same-site")
    }
}
