package site.qgames.userauth.service.user

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.userauth.entity.user.IdentityProvider
import site.qgames.userauth.entity.user.UserEntity
import site.qgames.userauth.entity.user.UserIdentityEntity
import site.qgames.userauth.exception.auth.AuthErrors
import site.qgames.userauth.gatewayapi.dto.user.LoginMethod
import site.qgames.userauth.gatewayapi.dto.user.UserResponse
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
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun getUser(userId: UUID): UserResponse = describe(findUser(userId))

    @Transactional
    fun addPasswordLogin(userId: UUID, password: String): UserResponse {
        val user = findUser(userId)
        if (userIdentityRepository.existsByUserIdAndProvider(userId, IdentityProvider.PASSWORD)) {
            throw AuthErrors.loginMethodAlreadyLinked()
        }
        userIdentityRepository.save(
            UserIdentityEntity(
                userId = userId,
                provider = IdentityProvider.PASSWORD,
                subject = user.email,
                passwordHash = passwordEncoder.encode(password),
                createdAt = clock.instant(),
            ),
        )
        return describe(user)
    }

    @Transactional
    fun addGoogleLogin(userId: UUID, idToken: String): UserResponse {
        val user = findUser(userId)
        val google = googleIdTokenVerifier.verify(idToken)
        if (userIdentityRepository.existsByUserIdAndProvider(userId, IdentityProvider.GOOGLE)) {
            throw AuthErrors.loginMethodAlreadyLinked()
        }
        if (userIdentityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, google.subject) != null) {
            throw AuthErrors.googleAccountLinkedToAnotherUser()
        }
        userIdentityRepository.save(
            UserIdentityEntity(userId = userId, provider = IdentityProvider.GOOGLE, subject = google.subject, createdAt = clock.instant()),
        )
        return describe(user)
    }

    fun describe(user: UserEntity): UserResponse {
        val loginMethods = userIdentityRepository.findAllByUserId(user.id!!)
            .map { LoginMethod.valueOf(it.provider.name) }
            .sorted()
        return UserResponse(user.id, user.email, user.emailVerified, loginMethods)
    }

    private fun findUser(userId: UUID): UserEntity =
        userRepository.findById(userId).orElseThrow { AuthErrors.userNotFound() }
}
