package site.qgames.admingateway.handler

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import site.qgames.common.exception.exception.ErrorCodes

/**
 * Ошибки целевых сервисов (ProblemDetail) отдаются фронту админки как есть: статус, тело и тип контента.
 * Приоритет выше, чем у ловушки Exception из libs/common-exception.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DownstreamExceptionHandler {

    @ExceptionHandler(RestClientResponseException::class)
    fun handleDownstreamError(ex: RestClientResponseException): ResponseEntity<ByteArray> {
        val headers = HttpHeaders()
        ex.responseHeaders?.contentType?.let { headers.contentType = it }
        return ResponseEntity.status(ex.statusCode).headers(headers).body(ex.responseBodyAsByteArray)
    }

    @ExceptionHandler(ResourceAccessException::class)
    fun handleUnavailable(ex: ResourceAccessException): ResponseEntity<ProblemDetail> {
        log.warn("Downstream service is unavailable", ex)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service is temporarily unavailable")
        problem.setProperty(ErrorCodes.CODE_PROPERTY, "SERVICE_UNAVAILABLE")
        // Тип задан явно, как в user-gateway: ошибка — всегда ProblemDetail, независимо от Accept запроса.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DownstreamExceptionHandler::class.java)
    }
}
