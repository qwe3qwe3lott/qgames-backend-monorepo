package site.qgames.common.test.container

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import site.qgames.common.test.mail.MailpitClient

/**
 * SMTP-ловушка Mailpit: письма реально уходят по SMTP, а тест читает их через HTTP API.
 */
object MailpitTestContainer {

    private const val SMTP_PORT = 1025
    private const val HTTP_PORT = 8025

    private val container: GenericContainer<*> = GenericContainer("axllent/mailpit:v1.27")
        .withExposedPorts(SMTP_PORT, HTTP_PORT)
        .waitingFor(Wait.forHttp("/readyz").forPort(HTTP_PORT))
        .apply { start() }

    val client: MailpitClient by lazy {
        MailpitClient("http://${container.host}:${container.getMappedPort(HTTP_PORT)}")
    }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.mail.host", container::getHost)
        registry.add("spring.mail.port") { container.getMappedPort(SMTP_PORT) }
    }
}
