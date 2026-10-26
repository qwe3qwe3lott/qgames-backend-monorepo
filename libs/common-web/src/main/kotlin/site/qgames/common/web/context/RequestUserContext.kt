package site.qgames.common.web.context

import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import site.qgames.common.exception.exception.UnauthorizedException
import java.util.UUID

/**
 * Пользователь текущего запроса. Нужен контроллерам, которые реализуют интерфейсы из `<m>-api`: в сигнатурах контракта
 * userId нет. Значение — `sub` проверенного access-JWT из SecurityContext (Security-цепочка приложения): в цепочке
 * портала это id игрока, в цепочке админки — id администратора.
 */
class RequestUserContext {

    fun userId(): UUID {
        val authentication = SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
            ?: throw unauthenticated()
        return runCatching { UUID.fromString(authentication.name) }.getOrElse { throw unauthenticated() }
    }

    private fun unauthenticated() =
        UnauthorizedException("UNAUTHENTICATED", "Request is not authenticated")
}
