package site.qgames.adminauth.support

import java.time.Instant
import java.util.UUID

/**
 * Фикстуры из src/test/resources/init.sql. Значения должны совпадать с SQL.
 */
object TestData {

    /** Пароль всех фикстурных администраторов. */
    const val PASSWORD = "correct-horse-1"

    const val FIXTURE_ADMIN_COUNT = 2L

    object Root {
        val ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000a1")
        const val LOGIN = "root"
        val CREATED_AT: Instant = Instant.parse("2026-10-02T00:00:00Z")
    }

    /** Создан раньше [Root], хотя id у него больше. */
    object Operator {
        val ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000a2")
        const val LOGIN = "operator"
        val CREATED_AT: Instant = Instant.parse("2026-10-01T00:00:00Z")
    }
}
