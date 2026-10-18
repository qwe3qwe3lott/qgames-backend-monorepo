package site.qgames.common.web.exception

import org.springframework.http.HttpStatus

/**
 * Бизнес-ошибка, которая отдаётся клиенту как ProblemDetail с машиночитаемым [code].
 */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
) : RuntimeException(message)
