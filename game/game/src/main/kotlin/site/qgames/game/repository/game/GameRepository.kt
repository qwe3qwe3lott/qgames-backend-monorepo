package site.qgames.game.repository.game

import org.springframework.stereotype.Repository
import site.qgames.game.mapper.game.GameEntityMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GameModel

/** Каталог игр. Строки заводятся миграциями (DML), статус публикации позже будет менять админка. */
@Repository
class GameRepository(
    private val gamePostgresqlRepository: GamePostgresqlRepository,
    private val gameEntityMapper: GameEntityMapper,
) {

    /** Все игры в порядке показа в каталоге. */
    fun findAll(): List<GameModel> = gamePostgresqlRepository.findAll().map(gameEntityMapper::toModel)

    /** Все коды из таблицы строками, как есть: для сверки с кодом приложения при старте. */
    fun findAllCodes(): List<String> = gamePostgresqlRepository.findAll().map { it.code }

    fun findByCode(code: GameCode): GameModel? = gamePostgresqlRepository.findByCode(code.code)?.let(gameEntityMapper::toModel)
}
