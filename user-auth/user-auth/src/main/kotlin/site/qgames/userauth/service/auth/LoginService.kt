package site.qgames.userauth.service.auth

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.model.user.UserModel
import site.qgames.userauth.exception.auth.AuthErrors
import site.qgames.userauth.model.auth.AuthResultModel
import site.qgames.userauth.repository.user.UserIdentityRepository
import site.qgames.userauth.repository.user.UserRepository
import site.qgames.userauth.service.user.UserService
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.publisher.user.UserProfilePublisher
import site.qgames.userauth.util.Nicknames
import java.time.Clock

@Service
class LoginService(
    private val userRepository: UserRepository,
    private val userIdentityRepository: UserIdentityRepository,
    private val passwordEncoder: PasswordEncoder,
    private val googleIdTokenVerifier: GoogleIdTokenVerifier,
    private val tokenService: TokenService,
    private val userService: UserService,
    private val userProfilePublisher: UserProfilePublisher,
    private val clock: Clock,
) {

    /** Хеш-заглушка, чтобы время ответа не выдавало, существует ли пользователь. */
    private val dummyPasswordHash: String by lazy { passwordEncoder.encode("dummy-password")!! }

    @Transactional
    fun loginWithPassword(rawEmail: String, password: String): AuthResultModel {
        val identity = userIdentityRepository.findByProviderAndSubject(IdentityProvider.PASSWORD, rawEmail.trim().lowercase())
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

    /**
     * Вход через Google — единственный способ создать аккаунт. Автопривязки по email нет: если аккаунт с таким email
     * уже есть, Google к нему привязывается только явно, из залогиненной сессии (ACCOUNT_LINK_REQUIRED).
     * Email в Google должен быть подтверждён: он становится логином для входа по паролю.
     */
    @Transactional
    fun loginWithGoogle(idToken: String): AuthResultModel {
        val google = googleIdTokenVerifier.verify(idToken)
        val linked = userIdentityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, google.subject)
        if (linked != null) {
            return startSession(userRepository.findById(linked.userId) ?: throw AuthErrors.userNotFound())
        }
        if (!google.emailVerified) {
            throw AuthErrors.googleEmailNotVerified()
        }
        if (userRepository.findByEmail(google.email) != null) {
            throw AuthErrors.accountLinkRequired()
        }
        val now = clock.instant()
        // Формы регистрации нет: ник генерируется, пользователь сменит его в профиле.
        val user = userRepository.insert(google.email, nickname = Nicknames.generate(), avatar = UserAvatar.CAT, now = now)
        userProfilePublisher.publish(user)
        userIdentityRepository.insert(user.id, IdentityProvider.GOOGLE, google.subject, passwordHash = null, now = now)
        return startSession(user)
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
}
