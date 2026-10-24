package site.qgames.app.config

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.models.GroupedOpenApi
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import site.qgames.adminauth.api.cookie.AuthCookieNames
import site.qgames.game.api.dto.session.MoveDataSchemas

/**
 * Две группы OpenAPI: `portal` (`/v3/api-docs/portal`, фронт портала) и `admin` (`/v3/api-docs/admin`, админка).
 * Общие кастомайзеры из libs/common-web — GlobalOpenApiCustomizer и применяются к обеим группам.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun portalOpenApi(properties: SpringDocConfigProperties): GroupedOpenApi =
        GroupedOpenApi.builder()
            .group(PORTAL_GROUP)
            .pathsToMatch("/api/**")
            .pathsToExclude("${AuthCookieNames.ADMIN_PATH}/**")
            .addOpenApiCustomizer(moveDataSchemas(properties))
            .build()

    @Bean
    fun adminOpenApi(): GroupedOpenApi =
        GroupedOpenApi.builder()
            .group(ADMIN_GROUP)
            .pathsToMatch("${AuthCookieNames.ADMIN_PATH}/**")
            .build()

    /**
     * Схемы параметров ходов игр (`CheckersMoveDataDto` и т.п.) — в components портала. `data` в общем конверте хода
     * (MoveRequest) остаётся свободным объектом, а клиент типизирует параметры хода своей игры по этим схемам. Ни одна
     * ручка на них не ссылается, поэтому springdoc сам их не публикует. Схемы строятся тем же набором конвертеров
     * (в том числе required по Kotlin), что и остальные.
     */
    private fun moveDataSchemas(properties: SpringDocConfigProperties) = OpenApiCustomizer { openApi ->
        val converters = ModelConverters.getInstance(properties.isOpenapi31)
        MoveDataSchemas.CLASSES.forEach { type ->
            converters.readAll(AnnotatedType(type)).forEach { (name, schema) -> openApi.components.addSchemas(name, schema) }
        }
    }

    companion object {
        const val PORTAL_GROUP = "portal"
        const val ADMIN_GROUP = "admin"
    }
}
