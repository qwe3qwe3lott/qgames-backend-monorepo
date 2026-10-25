package site.qgames.game.repository.game

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import site.qgames.game.mapper.game.GameRowMapper
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GameModel
import site.qgames.game.table.game.GameTable

/** Таблица `game` (Exposed). Используется только из [GameRepository]. */
@Repository
@Transactional
class GameExposedRepository(private val gameRowMapper: GameRowMapper) {

    fun findAll(): List<GameModel> =
        GameTable.selectAll()
            .orderBy(GameTable.sortOrder to SortOrder.ASC, GameTable.code to SortOrder.ASC)
            .map(gameRowMapper::toGame)

    fun findAllCodes(): List<String> = GameTable.select(GameTable.code).map { it[GameTable.code] }

    fun findByCode(code: GameCode): GameModel? =
        GameTable.selectAll().where { GameTable.code eq code.code }.singleOrNull()?.let(gameRowMapper::toGame)
}
