package site.qgames.userauth.repository.user

import org.springframework.data.repository.CrudRepository
import site.qgames.userauth.entity.user.UserEntity
import java.util.UUID

/** Таблица "user". Используется только из [UserRepository]. */
interface UserPostgresqlRepository : CrudRepository<UserEntity, UUID> {

    fun findByEmail(email: String): UserEntity?
}
