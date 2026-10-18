package site.qgames.common.web.context

import org.springframework.http.HttpStatus
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import site.qgames.common.web.exception.ApiException
import site.qgames.common.web.header.QgamesHeaders
import java.util.UUID

/**
 * Доступ к пользователю текущего запроса. Нужен контроллерам, которые реализуют интерфейсы из *-user-gateway-sync-api:
 * сигнатура таких методов не содержит userId, его проставляет interceptor gateway в заголовок [QgamesHeaders.USER_ID].
 */
class RequestUserContext {

    fun userId(): UUID {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        val header = request?.getHeader(QgamesHeaders.USER_ID) ?: throw unauthenticated()
        return runCatching { UUID.fromString(header) }.getOrElse { throw unauthenticated() }
    }

    private fun unauthenticated() =
        ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Request is not authenticated")
}
