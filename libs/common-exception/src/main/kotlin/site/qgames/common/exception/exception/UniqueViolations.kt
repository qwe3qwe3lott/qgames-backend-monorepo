package site.qgames.common.exception.exception

import java.sql.SQLException

/**
 * Нарушение уникальности (SQLSTATE `23505`) в цепочке причин исключения — без зависимости от ORM и драйвера
 * (Exposed бросает `ExposedSQLException` с драйверным исключением внутри).
 */
object UniqueViolations {

    private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

    fun isUniqueViolation(ex: Throwable): Boolean = uniqueViolation(ex) != null

    /**
     * Нарушено ли ограничение [constraint]. Имя ищется в сообщении драйвера (у PostgreSQL —
     * `duplicate key value violates unique constraint "<имя>"`), поэтому имя должно быть уникальным в БД.
     */
    fun isUniqueViolationOf(ex: Throwable, constraint: String): Boolean =
        uniqueViolation(ex)?.message?.contains("\"$constraint\"") == true

    private fun uniqueViolation(ex: Throwable): SQLException? =
        generateSequence(ex) { it.cause.takeIf { cause -> cause !== it } }
            .filterIsInstance<SQLException>()
            .firstOrNull { it.sqlState == UNIQUE_VIOLATION_SQL_STATE }
}
