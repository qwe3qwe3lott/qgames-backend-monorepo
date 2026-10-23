package site.qgames.userauth.repository.user

import org.springframework.stereotype.Repository
import site.qgames.userauth.entity.user.UserEntity
import site.qgames.userauth.mapper.user.UserEntityMapper
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.model.user.UserModel
import java.time.Instant
import java.util.UUID

@Repository
class UserRepository(
    private val userPostgresqlRepository: UserPostgresqlRepository,
    private val userEntityMapper: UserEntityMapper,
) {

    fun insert(email: String, nickname: String, avatar: UserAvatar, now: Instant): UserModel =
        userEntityMapper.toModel(
            userPostgresqlRepository.save(UserEntity(email = email, nickname = nickname, avatar = avatar.name, createdAt = now)),
        )

    /** Сохраняет изменения и увеличивает version; при параллельном изменении — OptimisticLockingFailureException (409). */
    fun update(user: UserModel): UserModel =
        userEntityMapper.toModel(userPostgresqlRepository.save(userEntityMapper.toEntity(user)))

    fun findById(id: UUID): UserModel? = userPostgresqlRepository.findById(id).orElse(null)?.let(userEntityMapper::toModel)

    fun findByEmail(email: String): UserModel? = userPostgresqlRepository.findByEmail(email)?.let(userEntityMapper::toModel)
}
