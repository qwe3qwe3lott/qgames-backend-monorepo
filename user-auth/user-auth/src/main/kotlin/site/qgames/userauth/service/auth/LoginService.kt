package site.qgames.userauth.service.auth

import org.springframework.beans.factory.annotation.Qualifier
import site.qgames.userauth.config.CryptoConfig
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.common.exception.exception.UniqueViolations
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.model.user.UserModel
import site.qgames.userauth.exception.auth.AuthErrors
import site.qgames.userauth.model.auth.AuthResultModel
import site.qgames.userauth.repository.user.UserIdentityRepository
import site.qgames.userauth.repository.user.UserRepository
import site.qgames.userauth.service.user.UserService
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.publisher.user.UserProfilePublisher
import java.time.Clock

@Service
class LoginService(
    private val userRepository: UserRepository,
    private val userIdentityRepository: UserIdentityRepository,
    @Qualifier(CryptoConfig.PASSWORD_ENCODER) private val passwordEncoder: PasswordEncoder,
    private val googleIdTokenVerifier: GoogleIdTokenVerifier,
    private val tokenService: TokenService,
    private val userService: UserService,
    private val userProfilePublisher: UserProfilePublisher,
    private val clock: Clock,
) {

    /** Хеш-заглушка, чтобы время ответа не выдавало, существует ли пользователь. */
    private val dummyPasswordHash: String by lazy { passwordEncoder.encode("dummy-password")!! }

    @Transactional
    fun loginWithPassword(rawLogin: String, password: String): AuthResultModel {
        val identity = userIdentityRepository.findByProviderAndSubject(IdentityProvider.PASSWORD, rawLogin.trim().lowercase())
        if (identity?.passwordHash == null) {
            passwordEncoder.matches(password, dummyPasswordHash)
            throw AuthErrors.invalidCredentials()
        }
        if (!passwordEncoder.matches(password, identity.passwordHash)) {
            throw AuthErrors.invalidCredentials()
        }
        val user = userRepository.findById(identity.userId) ?: throw AuthErrors.invalidCredentials()
        return startSession(user)
    }

    /** Вход через привязанный Google. Аккаунт здесь не создаётся: для нового Google-аккаунта — REGISTRATION_REQUIRED. */
    @Transactional
    fun loginWithGoogle(idToken: String): AuthResultModel {
        val google = googleIdTokenVerifier.verify(idToken)
        val linked = userIdentityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, google.subject)
            ?: throw AuthErrors.registrationRequired()
        return startSession(userRepository.findById(linked.userId) ?: throw AuthErrors.userNotFound())
    }

    /**
     * Регистрация через Google — единственный способ создать аккаунт (подтверждать личность почтой не нужно).
     * Логин задаётся здесь один раз и не меняется, ник сначала равен логину. Если передан [password], сразу
     * добавляется вход по логину и паролю. Параллельная регистрация того же логина или того же Google-аккаунта
     * проходит предпроверки и упирается в уникальные индексы — тогда отвечаем теми же кодами, что и без гонки.
     */
    @Transactional
    fun registerWithGoogle(idToken: String, login: String, password: String?): AuthResultModel {
        val google = googleIdTokenVerifier.verify(idToken)
        if (userIdentityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, google.subject) != null) {
            throw AuthErrors.accountAlreadyExists()
        }
        if (userRepository.findByLogin(login) != null) {
            throw AuthErrors.loginTaken()
        }
        val now = clock.instant()
        val user = try {
            userRepository.insert(login, nickname = login, avatar = UserAvatar.CAT, now = now).also {
                userIdentityRepository.insert(it.id, IdentityProvider.GOOGLE, google.subject, passwordHash = null, now = now)
                if (password != null) {
                    userIdentityRepository.insert(it.id, IdentityProvider.PASSWORD, login, passwordEncoder.encode(password), now)
                }
            }
        } catch (ex: Exception) {
            throw translateRegistrationRace(ex)
        }
        // Событие — после всех вставок: при гонке регистрация откатывается, и снапшот публиковать нечего.
        userProfilePublisher.publish(user)
        return startSession(user)
    }

    /**
     * Гонка за уникальный индекс → код того же смысла, что у предпроверки; прочие ошибки — как есть. Ловится
     * [Exception]: `ExposedSQLException` наследует checked [java.sql.SQLException], а не [RuntimeException].
     */
    private fun translateRegistrationRace(ex: Exception): Exception = when {
        UniqueViolations.isUniqueViolationOf(ex, USER_LOGIN_CONSTRAINT) -> AuthErrors.loginTaken()
        UniqueViolations.isUniqueViolationOf(ex, IDENTITY_SUBJECT_CONSTRAINT) -> AuthErrors.accountAlreadyExists()
        else -> ex
    }

    /**
     * Намеренно без внешней транзакции: при reuse [TokenService.rotate] должен закоммитить отзыв цепочки,
     * а внешняя транзакция откатила бы его вместе с ошибкой.
     */
    fun refresh(refreshToken: String): AuthResultModel {
        val (userId, tokens) = tokenService.rotate(refreshToken)
        val user = userRepository.findById(userId) ?: throw AuthErrors.invalidRefreshToken()
        return AuthResultModel(userService.describe(user), tokens)
    }

    private fun startSession(user: UserModel) =
        AuthResultModel(userService.describe(user), tokenService.issue(user.id))

    companion object {
        /** Уникальные ограничения из миграций user-auth (имена уникальны в БД). */
        private const val USER_LOGIN_CONSTRAINT = "user_login_uq"
        private const val IDENTITY_SUBJECT_CONSTRAINT = "user_identity_provider_subject_uq"
    }
}
