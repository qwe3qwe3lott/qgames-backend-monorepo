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

    fun gameNotFound() = NotFoundException("GAME_NOT_FOUND", "Unknown game code")

    fun gameTypeMismatch() = ConflictException("GAME_TYPE_MISMATCH", "The room is for another game")

    fun notRoomOwner() = ForbiddenException("NOT_ROOM_OWNER", "Only the room owner can do this")

    fun notRoomMember() = ForbiddenException("NOT_ROOM_MEMBER", "User is not in the room")

    fun notSessionPlayer() = ForbiddenException("NOT_SESSION_PLAYER", "User is not a player of the current game")

    fun roomNotWaiting() = ConflictException("ROOM_NOT_WAITING", "A game is in progress in the room")

    fun roomNotInGame() = ConflictException("ROOM_NOT_IN_GAME", "No game is in progress in the room")

    fun noFreeSeat() = ConflictException("NO_FREE_SEAT", "All player seats are taken")

    fun notEnoughPlayers(min: Int, max: Int) =
        ConflictException("NOT_ENOUGH_PLAYERS", "The game needs from $min to $max players")

    fun sessionConflict() =
        ConflictException("SESSION_CONFLICT", "The game has changed, reload the state and retry")

    fun moveRejected(reason: String, message: String) =
        UnprocessableException("MOVE_REJECTED", message, mapOf("reason" to reason))

    fun invalidMove() =
        ClientException(ErrorCodes.VALIDATION_ERROR, "Move does not match the game format")
}
