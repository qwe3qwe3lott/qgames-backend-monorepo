package site.qgames.common.exception.exception

import org.springframework.http.HttpStatus

/**
 * Корень ошибок, которые отдаются клиенту как ProblemDetail с машиночитаемым [code]. Статус задаёт класс ошибки,
 * а не место, где её бросают: сервисы выбирают подходящий наследник ([NotFoundException], [ConflictException] и т.д.).
 * [properties] — дополнительные поля ProblemDetail (например, причина отказа хода).
 */
abstract class QgamesException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val properties: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Ошибка клиента (4xx). Сама по себе — 400, конкретные статусы задают наследники. */
open class ClientException protected constructor(
    status: HttpStatus,
    code: String,
    message: String,
    properties: Map<String, Any?>,
    cause: Throwable?,
) : QgamesException(status, code, message, properties, cause) {

    constructor(code: String, message: String, properties: Map<String, Any?> = emptyMap(), cause: Throwable? = null) :
        this(HttpStatus.BAD_REQUEST, code, message, properties, cause)
}

class UnauthorizedException(code: String, message: String, properties: Map<String, Any?> = emptyMap(), cause: Throwable? = null) :
    ClientException(HttpStatus.UNAUTHORIZED, code, message, properties, cause)

class ForbiddenException(code: String, message: String, properties: Map<String, Any?> = emptyMap(), cause: Throwable? = null) :
    ClientException(HttpStatus.FORBIDDEN, code, message, properties, cause)

class NotFoundException(code: String, message: String, properties: Map<String, Any?> = emptyMap(), cause: Throwable? = null) :
    ClientException(HttpStatus.NOT_FOUND, code, message, properties, cause)

class ConflictException(code: String, message: String, properties: Map<String, Any?> = emptyMap(), cause: Throwable? = null) :
    ClientException(HttpStatus.CONFLICT, code, message, properties, cause)

class UnprocessableException(code: String, message: String, properties: Map<String, Any?> = emptyMap(), cause: Throwable? = null) :
    ClientException(HttpStatus.UNPROCESSABLE_CONTENT, code, message, properties, cause)

class TooManyRequestsException(code: String, message: String, properties: Map<String, Any?> = emptyMap(), cause: Throwable? = null) :
    ClientException(HttpStatus.TOO_MANY_REQUESTS, code, message, properties, cause)

/** Ошибка сервера (5xx). Сама по себе — 500, логируется с уровнем ERROR. */
open class ServerException protected constructor(
    status: HttpStatus,
    code: String,
    message: String,
    properties: Map<String, Any?>,
    cause: Throwable?,
) : QgamesException(status, code, message, properties, cause) {

    constructor(code: String, message: String, properties: Map<String, Any?> = emptyMap(), cause: Throwable? = null) :
        this(HttpStatus.INTERNAL_SERVER_ERROR, code, message, properties, cause)
}

class ServiceUnavailableException(code: String, message: String, properties: Map<String, Any?> = emptyMap(), cause: Throwable? = null) :
    ServerException(HttpStatus.SERVICE_UNAVAILABLE, code, message, properties, cause)
