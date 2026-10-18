package site.qgames.userauth.service.auth

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.userauth.entity.user.IdentityProvider
import site.qgames.userauth.entity.user.UserEntity
import site.qgames.userauth.entity.user.UserIdentityEntity
import site.qgames.userauth.exception.auth.AuthErrors
import site.qgames.userauth.gatewayapi.dto.auth.AuthResponse
import site.qgames.userauth.repository.user.UserIdentityRepository
import site.qgames.userauth.repository.user.UserRepository
import site.qgames.userauth.service.user.UserService
import java.time.Clock

@Service
class LoginService(
    private val userRepository: UserRepository,
    private val userIdentityRepository: UserIdentityRepository,
    private val passwordEncoder: PasswordEncoder,
    private val googleIdTokenVerifier: GoogleIdTokenVerifier,
    private val tokenService: TokenService,
    private val userService: UserService,
    private val clock: Clock,
) {

    /** Хеш-заглушка, чтобы время ответа не выдавало, существует ли пользователь. */
    private val dummyPasswordHash: String by lazy { passwordEncoder.encode("dummy-password")!! }

    @Transactional
    fun loginWithPassword(rawEmail: String, password: String): AuthResponse {
        val identity = userIdentityRepository.findByProviderAndSubject(IdentityProvider.PASSWORD, rawEmail.trim().lowercase())
        if (identity?.passwordHash == null) {
            passwordEncoder.matches(password, dummyPasswordHash)
            throw AuthErrors.invalidCredentials()
        }
        if (!passwordEncoder.matches(password, identity.passwordHash)) {
            throw AuthErrors.invalidCredentials()
        }
        val user = userRepository.findById(identity.userId).orElseThrow { AuthErrors.invalidCredentials() }
        if (!user.emailVerified) {
            throw AuthErrors.emailNotVerified()
        }
        return startSession(user)
    }

    /**
     * Вход через Google. Если по email уже есть аккаунт, Google привязывается к нему автоматически
     * только когда email подтверждён и у нас, и в Google — иначе возможен захват чужого аккаунта.
     */
    @Transactional
    fun loginWithGoogle(idToken: String): AuthResponse {
        val google = googleIdTokenVerifier.verify(idToken)
        val linked = userIdentityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, google.subject)
        if (linked != null) {
            return startSession(userRepository.findById(linked.userId).orElseThrow { AuthErrors.userNotFound() })
        }
        if (!google.emailVerified) {
            throw AuthErrors.googleEmailNotVerified()
        }
        val now = clock.instant()
        val existing = userRepository.findByEmail(google.email)
        val user = if (existing != null) {
            if (!existing.emailVerified || userIdentityRepository.existsByUserIdAndProvider(existing.id!!, IdentityProvider.GOOGLE)) {
                throw AuthErrors.accountLinkRequired()
            }
            existing
        } else {
            userRepository.save(UserEntity(email = google.email, emailVerified = true, createdAt = now))
        }
        userIdentityRepository.save(
            UserIdentityEntity(userId = user.id!!, provider = IdentityProvider.GOOGLE, subject = google.subject, createdAt = now),
        )
        return startSession(user)
    }

    /**
     * Намеренно без внешней транзакции: при reuse [TokenService.rotate] должен закоммитить отзыв цепочки,
     * а внешняя транзакция откатила бы его вместе с ошибкой.
     */
    fun refresh(refreshToken: String): AuthResponse {
        val (userId, tokens) = tokenService.rotate(refreshToken)
        val user = userRepository.findById(userId).orElseThrow { AuthErrors.invalidRefreshToken() }
        return AuthResponse(userService.describe(user), tokens)
    }

    private fun startSession(user: UserEntity) =
        AuthResponse(userService.describe(user), tokenService.issue(user.id!!))
}
