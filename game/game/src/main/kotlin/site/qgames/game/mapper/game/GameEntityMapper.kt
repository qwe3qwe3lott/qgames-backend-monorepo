package site.qgames.game.mapper.game

import org.springframework.stereotype.Component
import site.qgames.game.entity.game.GameEntity
import site.qgames.game.model.game.GameCode
import site.qgames.game.model.game.GameModel
import site.qgames.game.model.game.GamePublishStatus

@Component
class GameEntityMapper {

    fun toModel(entity: GameEntity) = GameModel(
        id = entity.id,
        code = GameCode.fromCode(entity.code) ?: error("Unknown game code '${entity.code}' in the game table"),
        title = entity.title,
        description = entity.description,
        sortOrder = entity.sortOrder,
        publishStatus = GamePublishStatus.valueOf(entity.publishStatus),
    )
}
