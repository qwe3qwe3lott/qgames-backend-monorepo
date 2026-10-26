package site.qgames.game.repository.game

import org.springframework.stereotype.Repository
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GameModel

/** Каталог игр. Строки заводятся миграциями (DML), статус публикации позже будет менять админка. */
@Repository
class GameRepository(
    private val gameExposedRepository: GameExposedRepository,
) {

    /** Все игры в порядке показа в каталоге. */
    fun findAll(): List<GameModel> = gameExposedRepository.findAll()

    /** Все коды из таблицы строками, как есть: для сверки с кодом приложения при старте. */
    fun findAllCodes(): List<String> = gameExposedRepository.findAllCodes()

    fun findByCode(code: GameCode): GameModel? = gameExposedRepository.findByCode(code)
}
