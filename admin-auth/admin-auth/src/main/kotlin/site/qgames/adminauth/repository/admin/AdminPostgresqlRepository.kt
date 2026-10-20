package site.qgames.adminauth.repository.admin

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import site.qgames.adminauth.entity.admin.AdminEntity
import java.util.UUID

/** Таблица admin. Используется только из [AdminRepository]. */
interface AdminPostgresqlRepository : CrudRepository<AdminEntity, UUID> {

    fun findByLogin(login: String): AdminEntity?

    @Query("SELECT * FROM admin ORDER BY created_at, id")
    fun findAllOrderByCreatedAt(): List<AdminEntity>

    /**
     * Вставляет администратора, только если таблица пуста. Одна команда, поэтому безопасна при старте нескольких
     * инстансов: одновременную вставку того же логина гасит ON CONFLICT. Возвращает число вставленных строк.
     */
    @Modifying
    @Query(
        """
        INSERT INTO admin (login, password_hash)
        SELECT :login, :passwordHash WHERE NOT EXISTS (SELECT 1 FROM admin)
        ON CONFLICT (login) DO NOTHING
        """,
    )
    fun insertIfNoAdmins(login: String, passwordHash: String): Int
}
