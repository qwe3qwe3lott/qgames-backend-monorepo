package site.qgames.userauth.repository.user

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import site.qgames.userauth.entity.user.UserEntity
import java.util.UUID

interface UserRepository : CrudRepository<UserEntity, UUID> {

    fun findByEmail(email: String): UserEntity?

    @Modifying
    @Query("""UPDATE "user" SET email_verified = true WHERE id = :id""")
    fun markEmailVerified(id: UUID): Int
}
