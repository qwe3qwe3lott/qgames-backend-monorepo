package site.qgames.common.cdc.source.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import site.qgames.common.cdc.source.publisher.OutboxPublisher

/**
 * Сервис-источник CDC должен сам создать таблицу `outbox_event` и публикацию `qgames_outbox` своей миграцией
 * (см. CLAUDE.md, раздел «Данные»).
 */
@AutoConfiguration
@EnableConfigurationProperties(OutboxProperties::class)
class CdcSourceAutoConfiguration {

    @Bean
    fun outboxPublisher(jdbcClient: JdbcClient, properties: OutboxProperties) = OutboxPublisher(jdbcClient, properties)
}
