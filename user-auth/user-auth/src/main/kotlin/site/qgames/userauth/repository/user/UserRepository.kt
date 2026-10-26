package site.qgames.userauth.repository.user

import org.springframework.stereotype.Repository
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.model.user.UserModel
import java.time.Instant
import java.util.UUID

@Repository
class UserRepository(
    private val userExposedRepository: UserExposedRepository,
) {

    /** Логин уникален: параллельная вставка того же логина — нарушение уникальности (409 CONFLICT). */
    fun insert(login: String, nickname: String, avatar: UserAvatar, now: Instant): UserModel =
        userExposedRepository.insert(login, nickname, avatar, now)

    /** Сохраняет ник и аватар профиля: последняя запись побеждает. */
    fun updateProfile(user: UserModel): UserModel {
        userExposedRepository.updateProfile(user.id, user.nickname, user.avatar)
        return user
    }

    fun findById(id: UUID): UserModel? = userExposedRepository.findById(id)

    fun findByLogin(login: String): UserModel? = userExposedRepository.findByLogin(login)
}
