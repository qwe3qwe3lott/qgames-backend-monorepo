package site.qgames.common.web.config

import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource
import site.qgames.common.web.context.RequestUserContext
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

        @Bean
        fun problemDetailResponsesCustomizer() = ProblemDetailResponsesCustomizer()

        /** springdoc подхватывает бины ModelConverter сам. */
        @Bean
        fun kotlinRequiredPropertiesConverter() = KotlinRequiredPropertiesConverter()
    }
}
