package site.qgames.userauth.service.user

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.userauth.exception.auth.AuthErrors
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.model.user.UserDetailsModel
import site.qgames.userauth.model.user.UserModel
import site.qgames.userauth.publisher.user.UserProfilePublisher
import site.qgames.userauth.repository.user.UserIdentityRepository
import site.qgames.userauth.repository.user.UserRepository
import site.qgames.userauth.service.auth.GoogleIdTokenVerifier
import java.time.Clock
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userIdentityRepository: UserIdentityRepository,
    private val passwordEncoder: PasswordEncoder,
    private val googleIdTokenVerifier: GoogleIdTokenVerifier,
    private val userProfilePublisher: UserProfilePublisher,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun getUser(userId: UUID): UserDetailsModel = describe(findUser(userId))

    @Transactional
    fun addPasswordLogin(userId: UUID, password: String): UserDetailsModel {
        val user = findUser(userId)
        if (userIdentityRepository.existsByUserIdAndProvider(userId, IdentityProvider.PASSWORD)) {
            throw AuthErrors.loginMethodAlreadyLinked()
        }
        userIdentityRepository.insert(userId, IdentityProvider.PASSWORD, user.email, passwordEncoder.encode(password), clock.instant())
        return describe(user)
    }

    @Transactional
    fun addGoogleLogin(userId: UUID, idToken: String): UserDetailsModel {
        val user = findUser(userId)
        val google = googleIdTokenVerifier.verify(idToken)
        if (userIdentityRepository.existsByUserIdAndProvider(userId, IdentityProvider.GOOGLE)) {
            throw AuthErrors.loginMethodAlreadyLinked()
        }
        if (userIdentityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, google.subject) != null) {
            throw AuthErrors.googleAccountLinkedToAnotherUser()
        }
        userIdentityRepository.insert(userId, IdentityProvider.GOOGLE, google.subject, passwordHash = null, now = clock.instant())
        return describe(user)
    }

    /** Меняет переданные поля профиля и публикует новый снапшот для других сервисов (CDC). */
    @Transactional
    fun updateProfile(userId: UUID, nickname: String?, avatar: UserAvatar?): UserDetailsModel {
        if (nickname == null && avatar == null) {
            throw AuthErrors.nothingToUpdate()
        }
        val user = findUser(userId)
        val changed = user.copy(nickname = nickname ?: user.nickname, avatar = avatar ?: user.avatar)
        // Без изменений не сохраняем: @Version увеличил бы версию, и событие ушло бы впустую.
        if (changed == user) {
            return describe(user)
        }
        val updated = userRepository.update(changed)
        userProfilePublisher.publish(updated)
        return describe(updated)
    }

    /** Пользователь вместе с привязанными способами входа (в порядке enum). */
    fun describe(user: UserModel): UserDetailsModel =
        UserDetailsModel(user, userIdentityRepository.findAllByUserId(user.id).map { it.provider }.sorted())

    private fun findUser(userId: UUID): UserModel =
        userRepository.findById(userId) ?: throw AuthErrors.userNotFound()
}
