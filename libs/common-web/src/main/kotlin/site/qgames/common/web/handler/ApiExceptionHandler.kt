package site.qgames.common.web.handler

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import site.qgames.common.web.exception.ApiException

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(ex.status, ex.message)
        problem.setProperty(CODE_PROPERTY, ex.code)
        return ResponseEntity.status(ex.status).body(problem)
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed")
        problem.setProperty(CODE_PROPERTY, VALIDATION_ERROR_CODE)
        problem.setProperty("errors", ex.bindingResult.fieldErrors.associate { it.field to it.defaultMessage })
        return ResponseEntity.badRequest().body(problem)
    }

    companion object {
        const val CODE_PROPERTY = "code"
        const val VALIDATION_ERROR_CODE = "VALIDATION_ERROR"
    }
}
