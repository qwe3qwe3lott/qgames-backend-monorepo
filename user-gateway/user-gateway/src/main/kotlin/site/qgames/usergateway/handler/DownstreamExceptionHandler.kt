package site.qgames.usergateway.handler

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import site.qgames.common.web.handler.ApiExceptionHandler

/**
 * Ошибки целевых сервисов (ProblemDetail) отдаются фронту как есть: статус, тело и тип контента.
 */
@RestControllerAdvice
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
        problem.setProperty(ApiExceptionHandler.CODE_PROPERTY, "SERVICE_UNAVAILABLE")
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DownstreamExceptionHandler::class.java)
    }
}
