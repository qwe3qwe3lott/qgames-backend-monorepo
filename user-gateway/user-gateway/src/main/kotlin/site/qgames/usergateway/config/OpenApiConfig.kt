package site.qgames.usergateway.config

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import site.qgames.game.gatewayapi.dto.session.MoveDataSchemas

@Configuration
class OpenApiConfig {

    /**
     * Схемы параметров ходов игр (`CheckersMoveDataDto` и т.п.) — в components. `data` в общем конверте хода
     * (MoveRequest) остаётся свободным объектом, а клиент типизирует параметры хода своей игры по этим схемам. Ни одна
     * ручка на них не ссылается, поэтому springdoc сам их не публикует. Схемы строятся тем же набором конвертеров
     * (в том числе required по Kotlin), что и остальные.
     */
    @Bean
    fun moveDataSchemasCustomizer(properties: SpringDocConfigProperties) = OpenApiCustomizer { openApi ->
        val converters = ModelConverters.getInstance(properties.isOpenapi31)
        MoveDataSchemas.CLASSES.forEach { type ->
            converters.readAll(AnnotatedType(type)).forEach { (name, schema) -> openApi.components.addSchemas(name, schema) }
        }
    }
}
