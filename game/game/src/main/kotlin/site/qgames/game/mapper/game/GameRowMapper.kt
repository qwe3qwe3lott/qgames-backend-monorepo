package site.qgames.game.mapper.game

import org.jetbrains.exposed.v1.core.ResultRow
import org.springframework.stereotype.Component
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GameModel
import site.qgames.game.model.game.GamePublishStatus
import site.qgames.game.table.game.GameTable

/** Строки таблицы `game` → модели. */
@Component
class GameRowMapper {

    fun toGame(row: ResultRow): GameModel {
        val code = row[GameTable.code]
        return GameModel(
            id = row[GameTable.id],
            code = GameCode.fromCode(code) ?: error("Unknown game code '$code' in the game table"),
            title = row[GameTable.title],
            description = row[GameTable.description],
            sortOrder = row[GameTable.sortOrder],
            publishStatus = GamePublishStatus.valueOf(row[GameTable.publishStatus]),
        )
    }
}
