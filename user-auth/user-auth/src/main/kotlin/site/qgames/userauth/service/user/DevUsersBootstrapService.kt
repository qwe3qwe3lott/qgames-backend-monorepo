package site.qgames.userauth.service.user

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import site.qgames.userauth.config.AuthProperties
import site.qgames.userauth.config.CryptoConfig
import site.qgames.userauth.model.user.IdentityProvider
import site.qgames.userauth.model.user.UserAvatar
import site.qgames.userauth.publisher.user.UserProfilePublisher
import site.qgames.userauth.repository.user.UserIdentityRepository
import site.qgames.userauth.repository.user.UserRepository
import java.time.Clock

/**
 * Тестовые игроки локального стенда: создаются при старте (после миграций) из qgames.auth.dev-users (задаёт профиль
 * local). Игрок n из 1..count — логин и ник `<prefix>n`, вход по общему паролю. Создаются только отсутствующие по
 * логину, существующие не меняются. Без настроек бутстрап выключен.
 */
@Service
class DevUsersBootstrapService(
    private val properties: AuthProperties,
    private val userRepository: UserRepository,
    private val userIdentityRepository: UserIdentityRepository,
    @Qualifier(CryptoConfig.PASSWORD_ENCODER) private val passwordEncoder: PasswordEncoder,
    private val userProfilePublisher: UserProfilePublisher,
    private val clock: Clock,
) : ApplicationRunner {

    /** Транзакция здесь, а не только у [bootstrap]: вызов изнутри бина идёт мимо прокси. */
    @Transactional
    override fun run(args: ApplicationArguments) {
        val devUsers = properties.devUsers
        bootstrap(devUsers.loginPrefix, devUsers.count, devUsers.password)
    }

    /**
     * Создаёт отсутствующих игроков [prefix]1..[prefix][count] с паролем [password]. count <= 0 выключает бутстрап;
     * при count > 0 без префикса или пароля — ошибка конфигурации, сервис не стартует. Возвращает число созданных.
     */
    @Transactional
    fun bootstrap(prefix: String?, count: Int, password: String?): Int {
        if (count <= 0) {
            return 0
        }
        val configuredPrefix = prefix?.takeIf { it.isNotBlank() }
        val configuredPassword = password?.takeIf { it.isNotBlank() }
        check(configuredPrefix != null && configuredPassword != null) {
            "Dev users bootstrap needs qgames.auth.dev-users.login-prefix and qgames.auth.dev-users.password"
        }
        val now = clock.instant()
        var created = 0
        for (n in 1..count) {
            val login = configuredPrefix + n
            if (userRepository.findByLogin(login) != null) {
                continue
            }
            val user = userRepository.insert(login, nickname = login, avatar = UserAvatar.CAT, now = now)
            userProfilePublisher.publish(user)
            userIdentityRepository.insert(user.id, IdentityProvider.PASSWORD, login, passwordEncoder.encode(configuredPassword), now)
            created++
        }
        if (created > 0) {
            log.warn("Created {} dev users '{}1..{}{}' from qgames.auth.dev-users", created, configuredPrefix, configuredPrefix, count)
        }
        return created
    }

    companion object {
        private val log = LoggerFactory.getLogger(DevUsersBootstrapService::class.java)
    }
}
