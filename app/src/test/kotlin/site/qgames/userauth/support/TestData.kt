package site.qgames.userauth.support

import java.util.UUID

/**
 * Фикстуры из app/src/test/resources/init/user-auth.sql. Значения должны совпадать с SQL.
 */
object TestData {

    /** Пароль всех фикстур со способом входа PASSWORD. */
    const val PASSWORD = "correct-horse-1"

    /** Все строки таблицы user в фикстурах: три аккаунта ниже и пять игроков тестов game (game.support.TestData). */
    const val FIXTURE_USER_COUNT = 8L

    /** Только вход по паролю (Google не привязан). */
    object PasswordOnly {
        val ID: UUID = UUID.fromString("01990000-0000-7000-8000-000000000001")
        const val LOGIN = "player"
    }

    /** Создан входом через Google, пароля нет. */
    object GoogleOnly {
        val ID: UUID = UUID.fromString("01990000-0000-7000-8000-000000000003")
        const val LOGIN = "google-owner"
        const val GOOGLE_SUB = "google-owner"
    }

    /** Привязаны и пароль, и Google. */
    object PasswordAndGoogle {
        val ID: UUID = UUID.fromString("01990000-0000-7000-8000-000000000004")
        const val LOGIN = "both"
        const val GOOGLE_SUB = "google-both"
    }
}
