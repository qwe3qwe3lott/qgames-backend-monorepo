package site.qgames.userauth.support

import java.util.UUID

/**
 * Фикстуры из src/test/resources/init.sql. Значения должны совпадать с SQL.
 */
object TestData {

    /** Пароль всех фикстур со способом входа PASSWORD. */
    const val PASSWORD = "correct-horse-1"

    const val FIXTURE_USER_COUNT = 4L

    /** Email, которого нет в фикстурах: для сценариев регистрации. */
    const val NEW_EMAIL = "newcomer@example.com"
    const val NEW_NICKNAME = "Newcomer"

    /** Подтверждённый email, вход по паролю. */
    object Verified {
        val ID: UUID = UUID.fromString("01990000-0000-7000-8000-000000000001")
        const val EMAIL = "player@example.com"
    }

    /** Email не подтверждён, вход по паролю. */
    object Unverified {
        val ID: UUID = UUID.fromString("01990000-0000-7000-8000-000000000002")
        const val EMAIL = "unverified@example.com"
    }

    /** Зарегистрирован через Google, пароля нет. */
    object GoogleOnly {
        val ID: UUID = UUID.fromString("01990000-0000-7000-8000-000000000003")
        const val EMAIL = "owner@gmail.com"
        const val GOOGLE_SUB = "google-owner"
    }

    /** Привязаны и пароль, и Google. */
    object PasswordAndGoogle {
        val ID: UUID = UUID.fromString("01990000-0000-7000-8000-000000000004")
        const val EMAIL = "both@example.com"
        const val GOOGLE_SUB = "google-both"
    }
}
