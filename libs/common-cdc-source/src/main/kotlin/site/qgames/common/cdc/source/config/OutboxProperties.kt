package site.qgames.common.cdc.source.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("qgames.cdc.outbox")
data class OutboxProperties(
    /** В тестах выключается, чтобы проверить записанное событие в таблице. */
    val deleteAfterInsert: Boolean = true,
)
