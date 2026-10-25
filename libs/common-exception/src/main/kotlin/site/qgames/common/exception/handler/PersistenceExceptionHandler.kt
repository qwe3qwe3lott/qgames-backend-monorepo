package site.qgames.common.exception.handler

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import site.qgames.common.exception.exception.ErrorCodes
import java.sql.SQLException

/**
 * Гонки на уникальных ключах превращаются в 409: одновременная вставка того же ключа (например, создание аккаунта
 * с одним email). Нарушение уникальности узнаётся по SQLSTATE `23505` в цепочке причин — так обработчик не зависит
 * от ORM (Exposed бросает `ExposedSQLException` с драйверным исключением внутри). Остальные ошибки БД — как ловушка
 * [Exception] в [QgamesExceptionHandler]: лог ERROR и 500 без деталей.
 * Приоритет выше, чем у ловушки [Exception] в [QgamesExceptionHandler].
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
class PersistenceExceptionHandler {

    @ExceptionHandler(SQLException::class)
    fun handleSqlException(ex: SQLException): ResponseEntity<ProblemDetail> {
        if (isUniqueViolation(ex)) {
            val problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Resource was modified concurrently")
            problem.setProperty(ErrorCodes.CODE_PROPERTY, ErrorCodes.CONFLICT)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
        }
        log.error("Unhandled database error", ex)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error")
        problem.setProperty(ErrorCodes.CODE_PROPERTY, ErrorCodes.INTERNAL_ERROR)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem)
    }

    private fun isUniqueViolation(ex: Throwable): Boolean =
        generateSequence(ex) { it.cause.takeIf { cause -> cause !== it } }
            .any { it is SQLException && it.sqlState == UNIQUE_VIOLATION_SQL_STATE }

    companion object {
        private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        private val log = LoggerFactory.getLogger(PersistenceExceptionHandler::class.java)
    }
}
