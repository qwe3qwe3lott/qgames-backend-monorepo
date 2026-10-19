package site.qgames.common.exception.handler

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import site.qgames.common.exception.exception.ErrorCodes

/**
 * Гонки между инстансами превращаются в 409: одновременная вставка уникального ключа (например, регистрация
 * одного email) или параллельное изменение строки с `@Version` (оптимистичная блокировка).
 * Приоритет выше, чем у ловушки [Exception] в [QgamesExceptionHandler].
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
class PersistenceExceptionHandler {

    @ExceptionHandler(DuplicateKeyException::class, OptimisticLockingFailureException::class)
    fun handleConcurrentModification(): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Resource was modified concurrently")
        problem.setProperty(ErrorCodes.CODE_PROPERTY, ErrorCodes.CONFLICT)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
    }
}
