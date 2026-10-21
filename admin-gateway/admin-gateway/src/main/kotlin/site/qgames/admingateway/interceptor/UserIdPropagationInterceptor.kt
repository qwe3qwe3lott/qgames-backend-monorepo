package site.qgames.admingateway.interceptor

import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import site.qgames.common.web.header.QgamesHeaders

/**
 * Передаёт id аутентифицированного администратора в целевой сервис заголовком X-User-Id (тот же заголовок, что
 * у user-gateway: сервис читает его через RequestUserContext). Значение берётся только из проверенного JWT;
 * заголовки входящего запроса никогда не пробрасываются.
 */
@Component
class UserIdPropagationInterceptor : ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        request.headers.remove(QgamesHeaders.USER_ID)
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication is JwtAuthenticationToken) {
            request.headers.set(QgamesHeaders.USER_ID, authentication.token.subject)
        }
        return execution.execute(request, body)
    }
}
