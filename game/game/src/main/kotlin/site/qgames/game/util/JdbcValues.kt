package site.qgames.game.util

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** JDBC-драйвер PostgreSQL не принимает Instant напрямую — передаём Timestamp. */
fun Instant?.toTimestamp(): Timestamp? = this?.let(Timestamp::from)

fun ResultSet.getInstant(column: String): Instant? = getTimestamp(column)?.toInstant()

fun ResultSet.getUuid(column: String): UUID? = getObject(column, UUID::class.java)
