package site.qgames.game.entity.game

import java.util.UUID

/** Строка таблицы game. Код и статус хранятся строками, в модель их переводит GameEntityMapper. */
data class GameEntity(
    val id: UUID,
    val code: String,
    val title: String,
    val description: String,
    val sortOrder: Int,
    val publishStatus: String,
)
