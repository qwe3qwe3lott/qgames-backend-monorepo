package site.qgames.common.web.config

import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import site.qgames.common.web.context.RequestUserContext
import site.qgames.common.web.openapi.DiscriminatedUnionCustomizer
import site.qgames.common.web.openapi.KotlinRequiredPropertiesConverter
import site.qgames.common.web.openapi.ProblemDetailResponsesCustomizer

@AutoConfiguration
@PropertySource("classpath:common-web-defaults.properties")
class CommonWebAutoConfiguration {

    @Bean
    fun requestUserContext() = RequestUserContext()

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OpenApiCustomizer::class)
    class OpenApiConfiguration {

        init {
            // Потоковый ответ (ResponseBodyEmitter) описывается явным @ApiResponse с content; без этого springdoc
            // кладёт в components пустую схему ResponseBodyEmitter.
            SpringDocUtils.getConfig().addResponseTypeToIgnore(ResponseBodyEmitter::class.java)
        }

        @Bean
        fun problemDetailResponsesCustomizer() = ProblemDetailResponsesCustomizer()

        @Bean
        fun discriminatedUnionCustomizer() = DiscriminatedUnionCustomizer()

        /** springdoc подхватывает бины ModelConverter сам. */
        @Bean
        fun kotlinRequiredPropertiesConverter() = KotlinRequiredPropertiesConverter()
    }
}
