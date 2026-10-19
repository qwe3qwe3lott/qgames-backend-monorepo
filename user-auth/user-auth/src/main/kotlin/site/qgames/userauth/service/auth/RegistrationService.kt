package site.qgames.userauth.service.auth

import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.common.exception.exception.QgamesException
import site.qgames.userauth.config.AuthProperties
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.model.user.UserModel
import site.qgames.userauth.event.auth.VerificationCodeIssuedEvent
import site.qgames.userauth.exception.auth.AuthErrors
import site.qgames.userauth.model.auth.AuthResultModel
import site.qgames.userauth.model.auth.RegistrationResultModel
import site.qgames.userauth.repository.auth.EmailVerificationRepository
import site.qgames.userauth.repository.user.UserIdentityRepository
import site.qgames.userauth.repository.user.UserRepository
import site.qgames.userauth.service.user.UserService
import site.qgames.userauth.publisher.user.UserProfilePublisher
import site.qgames.userauth.util.Hashing
import site.qgames.userauth.model.user.UserAvatar
import java.time.Clock
import java.util.UUID

@Service
class RegistrationService(
    private val userRepository: UserRepository,
    private val userIdentityRepository: UserIdentityRepository,
    private val emailVerificationRepository: EmailVerificationRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService,
    private val userService: UserService,
    private val eventPublisher: ApplicationEventPublisher,
    private val userProfilePublisher: UserProfilePublisher,
    private val properties: AuthProperties,
    private val clock: Clock,
) {

    @Transactional
    fun register(rawEmail: String, password: String, nickname: String, avatar: UserAvatar): RegistrationResultModel {
        val email = rawEmail.trim().lowercase()
        if (userRepository.findByEmail(email) != null) {
            throw AuthErrors.emailAlreadyRegistered()
        }
        val now = clock.instant()
        val user = userRepository.insert(email, emailVerified = false, nickname = nickname, avatar = avatar, now = now)
        userIdentityRepository.insert(user.id, IdentityProvider.PASSWORD, email, passwordEncoder.encode(password), now)
        userProfilePublisher.publish(user)
        issueVerificationCode(user)
        return RegistrationResultModel(user.id, user.email)
    }

    /**
     * noRollbackFor нужен, чтобы засчитанная попытка сохранилась и при неверном коде.
     */
    @Transactional(noRollbackFor = [QgamesException::class])
    fun verifyEmail(rawEmail: String, code: String): AuthResultModel {
        val now = clock.instant()
        val user = userRepository.findByEmail(rawEmail.trim().lowercase())
            ?: throw AuthErrors.invalidVerificationCode()
        if (user.emailVerified) {
            throw AuthErrors.emailAlreadyVerified()
        }
        val verification = emailVerificationRepository.findActive(user.id)
            ?: throw AuthErrors.invalidVerificationCode()
        if (!verification.expiresAt.isAfter(now)) {
            throw AuthErrors.verificationCodeExpired()
        }
        if (emailVerificationRepository.registerAttempt(verification.id, properties.verification.maxAttempts) == 0) {
            throw AuthErrors.tooManyVerificationAttempts()
        }
        if (verification.codeHash != hashCode(user.id, code)) {
            throw AuthErrors.invalidVerificationCode()
        }
        if (emailVerificationRepository.consume(verification.id, now) == 0) {
            throw AuthErrors.invalidVerificationCode()
        }
        userRepository.markEmailVerified(user.id)
        val verifiedUser = user.copy(emailVerified = true)
        return AuthResultModel(userService.describe(verifiedUser), tokenService.issue(user.id))
    }

    /**
     * Для неизвестного или уже подтверждённого email письмо не отправляется, ответ успешный.
     * Полностью скрыть существование адреса не пытаемся (см. CLAUDE.md, §5): кулдаун отвечает 429.
     */
    @Transactional
    fun resendCode(rawEmail: String) {
        val user = userRepository.findByEmail(rawEmail.trim().lowercase()) ?: return
        if (user.emailVerified) {
            return
        }
        val latest = emailVerificationRepository.findActive(user.id)
        if (latest != null && latest.createdAt.plus(properties.verification.resendCooldown).isAfter(clock.instant())) {
            throw AuthErrors.resendCooldown()
        }
        issueVerificationCode(user)
    }

    private fun issueVerificationCode(user: UserModel) {
        val now = clock.instant()
        emailVerificationRepository.consumeAllActive(user.id, now)
        val code = Hashing.randomDigits(CODE_LENGTH)
        emailVerificationRepository.insert(user.id, hashCode(user.id, code), now.plus(properties.verification.codeTtl), now)
        eventPublisher.publishEvent(VerificationCodeIssuedEvent(user.email, code))
    }

    private fun hashCode(userId: UUID, code: String) = Hashing.sha256Hex("$userId:$code")

    companion object {
        private const val CODE_LENGTH = 6
    }
}
