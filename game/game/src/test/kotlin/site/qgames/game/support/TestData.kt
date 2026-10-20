package site.qgames.game.support

import site.qgames.game.gatewayapi.dto.room.GameType
import site.qgames.game.gatewayapi.dto.room.NothingSettingsDto
import site.qgames.game.gatewayapi.dto.room.RoomSettingsDto
import site.qgames.game.gatewayapi.dto.room.TimeControlDto
import site.qgames.game.gatewayapi.dto.room.TimeControlMode
import java.util.UUID

/**
 * Фикстуры из src/test/resources/init.sql. Значения должны совпадать с SQL.
 */
object TestData {

    val ALICE: UUID = UUID.fromString("01990000-0000-7000-8000-00000000000a")
    val BOB: UUID = UUID.fromString("01990000-0000-7000-8000-00000000000b")
    val CAROL: UUID = UUID.fromString("01990000-0000-7000-8000-00000000000c")
    val DAVE: UUID = UUID.fromString("01990000-0000-7000-8000-00000000000d")
    val EVE: UUID = UUID.fromString("01990000-0000-7000-8000-00000000000e")

    /** Пользователь, чей профиль ещё не реплицировался. */
    val STRANGER: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000ff")

    val NICKNAMES = mapOf(ALICE to "Alice", BOB to "Bob", CAROL to "Carol", DAVE to "Dave", EVE to "Eve")

    /** Максимум игроков «игры в ничто». */
    const val NOTHING_MAX_PLAYERS = 4

    /** Настройки «игры в ничто» по умолчанию (из её правил): 3 круга, без таймера. */
    val NOTHING_DEFAULT_SETTINGS = RoomSettingsDto(TimeControlDto(TimeControlMode.NONE, null), NothingSettingsDto(GameType.NOTHING, 3))
}
