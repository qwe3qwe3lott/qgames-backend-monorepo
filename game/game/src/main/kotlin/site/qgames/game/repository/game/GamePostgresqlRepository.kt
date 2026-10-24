package site.qgames.game.repository.game

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import site.qgames.game.entity.game.GameEntity
import site.qgames.game.util.getUuid

/** Таблица game. Используется только из [GameRepository]. */
@Repository
class GamePostgresqlRepository(private val jdbcClient: JdbcClient) {

    fun findAll(): List<GameEntity> =
        jdbcClient.sql("SELECT $COLUMNS FROM \"game#game\" ORDER BY sort_order, code").query(mapper).list()

    fun findByCode(code: String): GameEntity? =
        jdbcClient.sql("SELECT $COLUMNS FROM \"game#game\" WHERE code = :code")
            .param("code", code)
            .query(mapper)
            .optional()
            .orElse(null)

    private val mapper = RowMapper { rs, _ ->
        GameEntity(
            id = rs.getUuid("id")!!,
            code = rs.getString("code"),
            title = rs.getString("title"),
            description = rs.getString("description"),
            sortOrder = rs.getInt("sort_order"),
            publishStatus = rs.getString("publish_status"),
        )
    }

    companion object {
        private const val COLUMNS = "id, code, title, description, sort_order, publish_status"
    }
}
