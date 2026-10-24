package site.qgames.userauth.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories

/**
 * Репозитории Spring Data модуля. Имена бинов — полные имена классов: одноимённые репозитории есть у admin-auth.
 */
@Configuration
@EnableJdbcRepositories(basePackages = ["site.qgames.userauth.repository"], nameGenerator = FullyQualifiedAnnotationBeanNameGenerator::class)
class PersistenceConfig
