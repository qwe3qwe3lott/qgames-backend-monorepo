package site.qgames.common.exception.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Import
import site.qgames.common.exception.handler.PersistenceExceptionHandler
import site.qgames.common.exception.handler.QgamesExceptionHandler

/** Подключается автоматически вместе с библиотекой: обработчики ошибок для всех сервисов на Spring MVC. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(QgamesExceptionHandler::class, PersistenceExceptionHandler::class)
class CommonExceptionAutoConfiguration
