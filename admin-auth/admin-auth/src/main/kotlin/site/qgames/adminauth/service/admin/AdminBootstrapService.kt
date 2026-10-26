package site.qgames.adminauth.service.admin

import org.springframework.beans.factory.annotation.Qualifier
import site.qgames.adminauth.config.CryptoConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import site.qgames.adminauth.config.AuthProperties
import site.qgames.adminauth.repository.admin.AdminRepository
import java.time.Clock

/**
 * Первый администратор: создаётся при старте (после миграций), если администраторов ещё нет. Логин и пароль берутся
 * из qgames.admin-auth.bootstrap (env ADMIN_BOOTSTRAP_LOGIN / ADMIN_BOOTSTRAP_PASSWORD); без них бутстрап выключен.
 * Проверка «таблица пуста» и вставка идут в одной пишущей транзакции.
 */
@Service
class AdminBootstrapService(
    private val properties: AuthProperties,
    private val adminRepository: AdminRepository,
    @Qualifier(CryptoConfig.PASSWORD_ENCODER) private val passwordEncoder: PasswordEncoder,
    private val clock: Clock,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        bootstrap(properties.bootstrap.login, properties.bootstrap.password)
    }

    /**
     * Создаёт администратора [login] с паролем [password], если администраторов нет. Пустые значения выключают
     * бутстрап; заданное только одно из двух — ошибка конфигурации, сервис не стартует. Возвращает true, если
     * администратор создан.
     */
    fun bootstrap(login: String?, password: String?): Boolean {
        val configuredLogin = login?.takeIf { it.isNotBlank() }
        val configuredPassword = password?.takeIf { it.isNotBlank() }
        if (configuredLogin == null && configuredPassword == null) {
            return false
        }
        check(configuredLogin != null && configuredPassword != null) {
            "Admin bootstrap needs both ADMIN_BOOTSTRAP_LOGIN and ADMIN_BOOTSTRAP_PASSWORD"
        }
        val created = adminRepository.insertIfNoAdmins(configuredLogin, passwordEncoder.encode(configuredPassword)!!, clock.instant())
        if (created) {
            log.warn("No admins found, created the first admin '{}' from qgames.admin-auth.bootstrap", configuredLogin)
        }
        return created
    }

    companion object {
        private val log = LoggerFactory.getLogger(AdminBootstrapService::class.java)
    }
}
