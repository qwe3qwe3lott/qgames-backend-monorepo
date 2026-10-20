package site.qgames.admingateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer
import org.springframework.web.service.registry.ImportHttpServices
import site.qgames.adminauth.gatewayapi.api.AdminApi
import site.qgames.adminauth.gatewayapi.api.AuthApi
import site.qgames.admingateway.interceptor.AdminIdPropagationInterceptor

/**
 * Клиенты целевых сервисов строятся по интерфейсам из модулей *-admin-gateway-sync-api.
 * Базовый URL группы задаётся свойством spring.http.serviceclient.<группа>.base-url.
 */
@Configuration
@ImportHttpServices(group = "admin-auth", types = [AuthApi::class, AdminApi::class])
class HttpClientsConfig {

    @Bean
    fun adminIdPropagationConfigurer(interceptor: AdminIdPropagationInterceptor) =
        RestClientHttpServiceGroupConfigurer { groups ->
            groups.forEachClient { _, builder ->
                builder.requestInterceptor(interceptor)
            }
        }
}
