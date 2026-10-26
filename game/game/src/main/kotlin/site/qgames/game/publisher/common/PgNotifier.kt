package site.qgames.game.publisher.common

import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * `pg_notify(channel, payload)` в текущей транзакции. Единственное разрешённое место сырого SQL в коде (CLAUDE.md §4):
 * у Exposed нет DSL для LISTEN/NOTIFY. PostgreSQL доставляет уведомление слушателям только после коммита (при
 * откате — никогда) и в порядке коммитов, поэтому outbox не нужен. Слушает RoomEventListener.
 */
@Component
class PgNotifier {

    @Transactional
    fun notify(channel: String, payload: String) {
        TransactionManager.current().exec(
            "SELECT pg_notify(?, ?)",
            listOf(TextColumnType() to channel, TextColumnType() to payload),
            StatementType.SELECT,
        ) { }
    }
}
