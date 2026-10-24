package site.qgames.adminauth.service.auth

import org.springframework.beans.factory.annotation.Qualifier
import site.qgames.adminauth.config.CryptoConfig
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.adminauth.exception.auth.AuthErrors
import site.qgames.adminauth.model.auth.AuthResultModel
import site.qgames.adminauth.repository.admin.AdminRepository

@Service
class LoginService(
    private val adminRepository: AdminRepository,
    @Qualifier(CryptoConfig.PASSWORD_ENCODER) private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService,
) {

    /** Хеш-заглушка, чтобы время ответа не выдавало, существует ли логин. */
    private val dummyPasswordHash: String by lazy { passwordEncoder.encode("dummy-password")!! }

    /** Логин сравнивается точно: без приведения регистра и обрезки пробелов. */
    @Transactional
    fun login(login: String, password: String): AuthResultModel {
        val admin = adminRepository.findByLogin(login)
        if (admin == null) {
            passwordEncoder.matches(password, dummyPasswordHash)
            throw AuthErrors.invalidCredentials()
        }
        if (!passwordEncoder.matches(password, admin.passwordHash)) {
            throw AuthErrors.invalidCredentials()
        }
        return AuthResultModel(admin, tokenService.issue(admin.id))
    }

    /**
     * Намеренно без внешней транзакции: при reuse [TokenService.rotate] должен закоммитить отзыв цепочки,
     * а внешняя транзакция откатила бы его вместе с ошибкой.
     */
    fun refresh(refreshToken: String): AuthResultModel {
        val (adminId, tokens) = tokenService.rotate(refreshToken)
        val admin = adminRepository.findById(adminId) ?: throw AuthErrors.invalidRefreshToken()
        return AuthResultModel(admin, tokens)
    }
}
