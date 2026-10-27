package site.qgames.game.support

import site.qgames.game.api.dto.game.GameCode
import site.qgames.game.api.dto.room.CheckersAdvancedSettingsDto
import site.qgames.game.api.dto.room.CheckersSimpleSettingsDto
import site.qgames.game.api.dto.room.CheckersTime
import site.qgames.game.api.dto.room.CheckersVariant
import site.qgames.game.api.dto.room.NothingAdvancedSettingsDto
import site.qgames.game.api.dto.room.NothingSimpleSettingsDto
import site.qgames.game.api.dto.room.NothingTime
import site.qgames.game.api.dto.room.RoomOptionsDto
import site.qgames.game.api.dto.room.RoomSettingsDto
import site.qgames.game.api.dto.room.TimeControlDto
import java.util.UUID

/**
 * Фикстуры из app/src/test/resources/init/game.sql; игроки (строки таблицы user с ником и аватаром) — из
 * init/user-auth.sql. Значения должны совпадать с SQL.
 */
object TestData {

    val ALICE: UUID = UUID.fromString("01990000-0000-7000-8000-00000000000a")
    val BOB: UUID = UUID.fromString("01990000-0000-7000-8000-00000000000b")
    val CAROL: UUID = UUID.fromString("01990000-0000-7000-8000-00000000000c")
    val DAVE: UUID = UUID.fromString("01990000-0000-7000-8000-00000000000d")
    val EVE: UUID = UUID.fromString("01990000-0000-7000-8000-00000000000e")

    /** Пользователь без строки в таблице user: профиля нет (ник и аватар в ответах — null). */
    val STRANGER: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000ff")

    val NICKNAMES = mapOf(ALICE to "Alice", BOB to "Bob", CAROL to "Carol", DAVE to "Dave", EVE to "Eve")

    /**
     * Каталог игр (таблица game), как в DML-миграциях. «Игра в ничто» и шашки — PUBLISHED, Каркассон —
     * NOT_PUBLISHED без правил.
     */
    const val NOTHING_TITLE = "Игра в ничто"
    const val NOTHING_DESCRIPTION = "Тестовая игра: игроки по очереди завершают ход, партия длится заданное число раундов."
    const val CHECKERS_TITLE = "Шашки"
    const val CHECKERS_DESCRIPTION = "Шашки на двоих: русские, международные или английские — вариант правил выбирается в настройках комнаты."
    const val CARCASSONNE_TITLE = "Каркассон"
    const val CARCASSONNE_DESCRIPTION = "Строим города, дороги и монастыри. Игра в разработке."

    /** Максимум игроков «игры в ничто». */
    const val NOTHING_MAX_PLAYERS = 4

    /** Настройки «игры в ничто» по умолчанию (из её правил): лимит 8 наблюдателей, 3 круга, без таймера. */
    val NOTHING_DEFAULT_SETTINGS = RoomSettingsDto(
        room = RoomOptionsDto(8),
        simple = NothingSimpleSettingsDto(GameCode.NOTHING, rounds = 3, time = NothingTime.NONE),
        advanced = NothingAdvancedSettingsDto(GameCode.NOTHING, rounds = 3, timeControl = TimeControlDto(false, 30, 5)),
    )

    /** Настройки шашек по умолчанию (из их правил): русские, быстрые 8 мин + 4 с. */
    val CHECKERS_DEFAULT_SETTINGS = RoomSettingsDto(
        room = RoomOptionsDto(8),
        simple = CheckersSimpleSettingsDto(GameCode.CHECKERS, CheckersVariant.RUSSIAN, CheckersTime.RAPID),
        advanced = checkersAdvanced(CheckersVariant.RUSSIAN, TimeControlDto(true, 480, 4)),
    )

    /** Advanced-настройки шашек с правилами варианта [variant] по умолчанию. */
    fun checkersAdvanced(variant: CheckersVariant, timeControl: TimeControlDto = TimeControlDto(false, 480, 4)) = when (variant) {
        CheckersVariant.RUSSIAN -> CheckersAdvancedSettingsDto(GameCode.CHECKERS, variant, timeControl, false, true, true, true, false, true, true)
        CheckersVariant.INTERNATIONAL -> CheckersAdvancedSettingsDto(GameCode.CHECKERS, variant, timeControl, false, true, true, true, true, false, true)
        CheckersVariant.ENGLISH -> CheckersAdvancedSettingsDto(GameCode.CHECKERS, variant, timeControl, false, false, false, true, false, false, true)
    }
}
