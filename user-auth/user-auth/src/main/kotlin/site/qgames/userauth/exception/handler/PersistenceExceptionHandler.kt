package site.qgames.userauth.exception.handler

import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import site.qgames.common.web.handler.ApiExceptionHandler

/**
 * Гонка двух инстансов на уникальном ключе (например, одновременная регистрация одного email) превращается в 409.
 */
@RestControllerAdvice
class PersistenceExceptionHandler {

    @ExceptionHandler(DuplicateKeyException::class)
    fun handleDuplicateKey(): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Resource was modified concurrently")
        problem.setProperty(ApiExceptionHandler.CODE_PROPERTY, "CONFLICT")
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
    }
}
