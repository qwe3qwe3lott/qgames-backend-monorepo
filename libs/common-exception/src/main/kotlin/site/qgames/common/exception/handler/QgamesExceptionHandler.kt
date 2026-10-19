package site.qgames.common.exception.handler

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import site.qgames.common.exception.exception.ErrorCodes
import site.qgames.common.exception.exception.QgamesException
import site.qgames.common.exception.exception.ServerException

/**
 * Все ошибки — ProblemDetail с полем `code`. Стандартные исключения Spring MVC (404 маршрута, 405 и т.п.)
 * обрабатывает [ResponseEntityExceptionHandler]: в одном advice выбирается самый точный обработчик,
 * поэтому ловушка [Exception] до них не доходит.
 *
 * Самый низкий приоритет: собственные advice сервисов должны объявлять `@Order(Ordered.HIGHEST_PRECEDENCE)`,
 * иначе ловушка [Exception] может перехватить их исключения.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class QgamesExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(QgamesException::class)
    fun handleQgamesException(ex: QgamesException): ResponseEntity<ProblemDetail> {
        if (ex is ServerException) {
            log.error("Server error {}", ex.code, ex)
        }
        val problem = problem(ex.status, ex.code, ex.message)
        ex.properties.forEach(problem::setProperty)
        return ResponseEntity.status(ex.status).body(problem)
    }

    /** Непредвиденная ошибка: детали только в логе, клиенту — общий 500. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unexpected error", ex)
        return ResponseEntity.internalServerError()
            .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "Internal server error"))
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = problem(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "Request validation failed")
        problem.setProperty("errors", ex.bindingResult.fieldErrors.associate { it.field to it.defaultMessage })
        return ResponseEntity.badRequest().body(problem)
    }

    /** Тело не разобралось (неизвестное значение enum, нет обязательного поля): тот же код, что и у валидации. */
    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "Request body is malformed"))

    private fun problem(status: HttpStatusCode, code: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty(ErrorCodes.CODE_PROPERTY, code) }

    companion object {
        private val log = LoggerFactory.getLogger(QgamesExceptionHandler::class.java)
    }
}
