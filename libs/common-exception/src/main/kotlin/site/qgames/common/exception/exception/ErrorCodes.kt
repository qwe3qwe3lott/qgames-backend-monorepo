package site.qgames.common.exception.exception

/** Общие коды ошибок всех сервисов. Коды конкретных фич — в каталогах `<Фича>Errors` сервисов. */
object ErrorCodes {
    /** Имя поля ProblemDetail с машиночитаемым кодом. */
    const val CODE_PROPERTY = "code"

    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val CONFLICT = "CONFLICT"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}
