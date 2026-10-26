package site.qgames.game.table.game

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * Таблица `game` — каталог игр. Строки заводятся DML-миграциями. Код и статус публикации — строки (GameCode.code,
 * имя GamePublishStatus).
 */
object GameTable : Table("game") {
    val id = javaUUID("id")
    val code = varchar("code", 32)
    val title = text("title")
    val description = text("description")
    val sortOrder = integer("sort_order")
    val publishStatus = varchar("publish_status", 32)

    override val primaryKey = PrimaryKey(id)
}
