package site.qgames.game.repository.replica

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import site.qgames.game.entity.replica.UserAuthUserEntity
import site.qgames.game.util.getUuid
import java.util.UUID

/** Таблица-реплика "user_auth#user". Используется только из [UserAuthUserRepository]. */
@Repository
class UserAuthUserPostgresqlRepository(private val jdbcClient: JdbcClient) {

    fun upsert(user: UserAuthUserEntity): Boolean =
        jdbcClient.sql(
            """
            INSERT INTO "game#user_auth#user" (id, nickname, avatar, version)
            VALUES (:id, :nickname, :avatar, :version)
            ON CONFLICT (id) DO UPDATE
                SET nickname = EXCLUDED.nickname, avatar = EXCLUDED.avatar, version = EXCLUDED.version
                WHERE "game#user_auth#user".version < EXCLUDED.version
            """,
        )
            .param("id", user.id)
            .param("nickname", user.nickname)
            .param("avatar", user.avatar)
            .param("version", user.version)
            .update() > 0

    fun findByIds(ids: Collection<UUID>): List<UserAuthUserEntity> =
        jdbcClient.sql("""SELECT * FROM "game#user_auth#user" WHERE id IN (:ids)""")
            .param("ids", ids)
            .query(mapper)
            .list()

    private val mapper = RowMapper { rs, _ ->
        UserAuthUserEntity(
            id = rs.getUuid("id")!!,
            nickname = rs.getString("nickname"),
            avatar = rs.getString("avatar"),
            version = rs.getLong("version"),
        )
    }
}
