package site.qgames.userauth.service.user

import org.springframework.beans.factory.annotation.Qualifier
import site.qgames.userauth.config.CryptoConfig
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.userauth.exception.auth.AuthErrors
import site.qgames.userauth.model.auth.AuthResultModel
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.model.user.UserDetailsModel
import site.qgames.userauth.model.user.UserModel
import site.qgames.userauth.publisher.user.UserProfilePublisher
import site.qgames.userauth.repository.user.UserIdentityRepository
import site.qgames.userauth.repository.user.UserRepository
import site.qgames.userauth.service.auth.GoogleIdTokenVerifier
import site.qgames.userauth.service.auth.TokenService
import java.time.Clock
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userIdentityRepository: UserIdentityRepository,
    @Qualifier(CryptoConfig.PASSWORD_ENCODER) private val passwordEncoder: PasswordEncoder,
    private val googleIdTokenVerifier: GoogleIdTokenVerifier,
    private val userProfilePublisher: UserProfilePublisher,
    private val tokenService: TokenService,
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
        userIdentityRepository.insert(userId, IdentityProvider.PASSWORD, user.login, passwordEncoder.encode(password), clock.instant())
        return describe(user)
    }

    /**
     * Меняет пароль, если текущий верен, и завершает все сессии пользователя: при утечке старого пароля чужие
     * сессии теряют доступ. Клиенту, сменившему пароль, выдаётся новая сессия. Строка "user" не меняется.
     */
    @Transactional
    fun changePassword(userId: UUID, currentPassword: String, newPassword: String): AuthResultModel {
        val user = findUser(userId)
        val identity = userIdentityRepository.findByUserIdAndProvider(userId, IdentityProvider.PASSWORD)
        val currentHash = identity?.passwordHash ?: throw AuthErrors.passwordNotSet()
        if (!passwordEncoder.matches(currentPassword, currentHash)) {
            throw AuthErrors.invalidCurrentPassword()
        }
        // 0 строк — пароль уже сменил параллельный запрос, и текущий пароль проверен по устаревшему хешу.
        if (userIdentityRepository.updatePasswordHash(identity.id, currentHash, passwordEncoder.encode(newPassword)!!) == 0) {
            throw AuthErrors.invalidCurrentPassword()
        }
        tokenService.revokeAllSessions(userId)
        return AuthResultModel(describe(user), tokenService.issue(userId))
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

    /** Меняет переданные поля профиля и публикует новый снапшот для других модулей (событие UserProfileChanged). */
    @Transactional
    fun updateProfile(userId: UUID, nickname: String?, avatar: UserAvatar?): UserDetailsModel {
        if (nickname == null && avatar == null) {
            throw AuthErrors.nothingToUpdate()
        }
        val user = findUser(userId)
        val changed = user.copy(nickname = nickname ?: user.nickname, avatar = avatar ?: user.avatar)
        // Без изменений не сохраняем и событие не публикуем.
        if (changed == user) {
            return describe(user)
        }
        val updated = userRepository.updateProfile(changed)
        userProfilePublisher.publish(updated)
        return describe(updated)
    }

    /** Пользователь вместе с привязанными способами входа (в порядке enum). */
    fun describe(user: UserModel): UserDetailsModel =
        UserDetailsModel(user, userIdentityRepository.findAllByUserId(user.id).map { it.provider }.sorted())

    private fun findUser(userId: UUID): UserModel =
        userRepository.findById(userId) ?: throw AuthErrors.userNotFound()
}
