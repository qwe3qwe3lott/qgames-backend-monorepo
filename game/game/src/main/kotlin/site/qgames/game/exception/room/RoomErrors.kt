package site.qgames.game.exception.room

import site.qgames.common.exception.exception.ClientException
import site.qgames.common.exception.exception.ConflictException
import site.qgames.common.exception.exception.ErrorCodes
import site.qgames.common.exception.exception.ForbiddenException
import site.qgames.common.exception.exception.NotFoundException
import site.qgames.common.exception.exception.UnprocessableException

/**
 * Каталог ошибок комнат и партий. Коды — часть контракта с фронтом.
 */
object RoomErrors {

    fun roomNotFound() = NotFoundException("ROOM_NOT_FOUND", "Room not found")

    /** Неизвестный код игры или игра не опубликована (NOT_PUBLISHED). */
    fun gameNotFound() = NotFoundException("GAME_NOT_FOUND", "Unknown game code")

    fun gameTypeMismatch() = ConflictException("GAME_TYPE_MISMATCH", "The room is for another game")

    fun notRoomOwner() = ForbiddenException("NOT_ROOM_OWNER", "Only the room owner can do this")

    fun notRoomMember() = ForbiddenException("NOT_ROOM_MEMBER", "User is not in the room")

    /** Владение передают только присутствующему участнику комнаты. */
    fun newOwnerNotPresent() = ConflictException("NEW_OWNER_NOT_PRESENT", "The new owner is not present in the room")

    fun notSessionPlayer() = ForbiddenException("NOT_SESSION_PLAYER", "User is not a player of the current game")

    fun roomNotWaiting() = ConflictException("ROOM_NOT_WAITING", "A game is in progress in the room")

    fun roomNotInGame() = ConflictException("ROOM_NOT_IN_GAME", "No game is in progress in the room")

    fun noFreeSeat() = ConflictException("NO_FREE_SEAT", "All player seats are taken")

    /** Наблюдатели заняли все места по лимиту комнаты (settings.room.maxSpectators). */
    fun noSpectatorSeat() = ConflictException("NO_SPECTATOR_SEAT", "All spectator seats are taken")

    fun notEnoughPlayers(min: Int, max: Int) =
        ConflictException("NOT_ENOUGH_PLAYERS", "The game needs from $min to $max players")

    fun sessionNotFound() = NotFoundException("SESSION_NOT_FOUND", "Session not found in the room")

    /** Пауза — действие таймера: у партии без таймера её нет. */
    fun sessionNotPausable() = ConflictException("SESSION_NOT_PAUSABLE", "The game has no timer to pause")

    fun sessionPaused() = ConflictException("SESSION_PAUSED", "The game is paused")

    fun moveRejected(reason: String, message: String) =
        UnprocessableException("MOVE_REJECTED", message, mapOf("reason" to reason))

    /** Заголовок `X-Qgames-Seed` не число (только при включённом `seed-header-enabled`). */
    fun invalidSeed() =
        ClientException(ErrorCodes.VALIDATION_ERROR, "Seed header must be a 64-bit integer")

    fun invalidMove() =
        ClientException(ErrorCodes.VALIDATION_ERROR, "Move does not match the game format")

    /** [errors] — поле запроса → причина, в том же формате, что у ошибок `@Valid`. */
    fun invalidSettings(errors: Map<String, String>) =
        ClientException(ErrorCodes.VALIDATION_ERROR, "Room settings do not match the game", mapOf("errors" to errors))
}
