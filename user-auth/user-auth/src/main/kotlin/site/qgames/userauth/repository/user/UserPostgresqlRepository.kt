package site.qgames.userauth.repository.user

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import site.qgames.userauth.entity.user.UserEntity
import java.util.UUID

/** Таблица "user". Используется только из [UserRepository]. */
interface UserPostgresqlRepository : CrudRepository<UserEntity, UUID> {

    fun findByEmail(email: String): UserEntity?

    @Modifying
    @Query("""UPDATE "user" SET email_verified = true, version = version + 1 WHERE id = :id""")
    fun markEmailVerified(id: UUID): Int
}
