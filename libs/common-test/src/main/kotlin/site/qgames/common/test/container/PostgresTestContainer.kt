package site.qgames.common.test.container

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Один контейнер PostgreSQL на весь прогон тестов модуля (singleton): контекст Spring кешируется между
 * тестовыми классами, поэтому контейнер не должен перезапускаться.
 */
object PostgresTestContainer {

    private val container: PostgreSQLContainer = PostgreSQLContainer("postgres:18-alpine").apply { start() }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url", container::getJdbcUrl)
        registry.add("spring.datasource.username", container::getUsername)
        registry.add("spring.datasource.password", container::getPassword)
    }
}
