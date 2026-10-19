package site.qgames.common.cdc.consumer.config

import com.google.protobuf.InvalidProtocolBufferException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.PropertySource
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * Потребитель CDC-событий. Значения сообщений — protobuf-байты из `*-internal-async-api`, ключ — id агрегата.
 *
 * Значения по умолчанию (`cdc-consumer-defaults.properties`, приоритет ниже конфигурации сервиса):
 * consumer group = имя сервиса (все инстансы делят партиции), чтение с начала топика (реплика собирается с нуля),
 * десериализаторы String/byte[].
 */
@AutoConfiguration
@PropertySource("classpath:cdc-consumer-defaults.properties")
class CdcConsumerAutoConfiguration {

    /**
     * Временные ошибки (БД недоступна) повторяются, «ядовитые» сообщения (битый protobuf) логируются и пропускаются,
     * чтобы не блокировать партицию. Обработчики обязаны быть идемпотентными: доставка — at-least-once.
     */
    @Bean
    fun cdcErrorHandler(): CommonErrorHandler =
        DefaultErrorHandler(
            { record, ex -> log.error("Skipping CDC record {}-{}@{} key={}", record.topic(), record.partition(), record.offset(), record.key(), ex) },
            FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS),
        ).apply { addNotRetryableExceptions(InvalidProtocolBufferException::class.java, IllegalArgumentException::class.java) }

    companion object {
        private val log = LoggerFactory.getLogger(CdcConsumerAutoConfiguration::class.java)
        private const val RETRY_INTERVAL_MS = 1_000L
        private const val RETRY_ATTEMPTS = 5L
    }
}
