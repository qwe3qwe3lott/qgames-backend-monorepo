package site.qgames.common.test.container

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.TopicConfig
import org.apache.kafka.common.errors.TopicExistsException
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.redpanda.RedpandaContainer
import java.util.concurrent.ExecutionException

/**
 * Брокер с протоколом Kafka (как в docker-compose). Singleton на прогон тестов модуля.
 */
object RedpandaTestContainer {

    private val container: RedpandaContainer = RedpandaContainer("redpandadata/redpanda:v26.2.5").apply { start() }

    val bootstrapServers: String
        get() = container.bootstrapServers

    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.kafka.bootstrap-servers", container::getBootstrapServers)
    }

    /** Создаёт компактируемый топик, как CDC-топики в окружении. Повторный вызов безопасен. */
    fun createCompactedTopic(name: String) {
        AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers)).use { admin ->
            val topic = NewTopic(name, 1, 1.toShort()).configs(mapOf(TopicConfig.CLEANUP_POLICY_CONFIG to TopicConfig.CLEANUP_POLICY_COMPACT))
            try {
                admin.createTopics(listOf(topic)).all().get()
            } catch (ex: ExecutionException) {
                if (ex.cause !is TopicExistsException) throw ex
            }
        }
    }
}
