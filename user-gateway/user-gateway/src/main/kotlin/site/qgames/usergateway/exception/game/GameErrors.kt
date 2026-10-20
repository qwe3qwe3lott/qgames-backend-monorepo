package site.qgames.usergateway.exception.game

import site.qgames.common.exception.exception.NotFoundException

/**
 * Ошибки, которые gateway отдаёт сам, не вызывая game. Коды совпадают с кодами сервиса game.
 */
object GameErrors {

    fun gameNotFound() = NotFoundException("GAME_NOT_FOUND", "Unknown game code")
}
