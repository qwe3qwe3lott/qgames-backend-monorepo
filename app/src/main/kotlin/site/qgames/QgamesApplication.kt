package site.qgames

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Модульный монолит: модули приложения (Spring Modulith) — прямые подпакеты `site.qgames` (`userauth`, `adminauth`,
 * `game`) и `app` (Security, CORS, OpenAPI). Инфраструктура `site.qgames.common` и правила игр `site.qgames.games`
 * модулями не считаются.
 *
 * Имена бинов — полные имена классов: в модулях есть одноимённые классы (AuthController, TokenService у user-auth
 * и admin-auth). Расписание нужно джобам game.
 */
@SpringBootApplication(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator::class)
@ConfigurationPropertiesScan
@EnableScheduling
class QgamesApplication

fun main(args: Array<String>) {
    runApplication<QgamesApplication>(*args)
}
