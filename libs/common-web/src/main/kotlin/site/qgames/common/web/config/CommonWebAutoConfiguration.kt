package site.qgames.common.web.config

import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import site.qgames.common.web.context.RequestUserContext
import site.qgames.common.web.handler.ApiExceptionHandler
import site.qgames.common.web.openapi.ProblemDetailResponsesCustomizer

@AutoConfiguration
@Import(ApiExceptionHandler::class)
class CommonWebAutoConfiguration {

    @Bean
    fun requestUserContext() = RequestUserContext()

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OpenApiCustomizer::class)
    class OpenApiConfiguration {

        @Bean
        fun problemDetailResponsesCustomizer() = ProblemDetailResponsesCustomizer()
    }
}
