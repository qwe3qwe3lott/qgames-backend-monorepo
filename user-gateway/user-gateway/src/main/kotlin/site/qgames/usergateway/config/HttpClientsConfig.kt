package site.qgames.usergateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer
import org.springframework.web.service.registry.HttpServiceGroupConfigurer
import org.springframework.web.service.registry.ImportHttpServices
import site.qgames.game.gatewayapi.api.GameApi
import site.qgames.game.gatewayapi.api.GameRoomApi
import site.qgames.game.gatewayapi.api.RoomApi
import site.qgames.userauth.gatewayapi.api.AuthApi
import site.qgames.userauth.gatewayapi.api.UserApi
import site.qgames.usergateway.interceptor.UserIdPropagationInterceptor

/**
 * Клиенты целевых сервисов строятся по интерфейсам из модулей *-user-gateway-sync-api.
 * Базовый URL группы задаётся свойством spring.http.serviceclient.<группа>.base-url.
 */
@Configuration
@ImportHttpServices(group = "user-auth", types = [AuthApi::class, UserApi::class])
@ImportHttpServices(group = "game", types = [RoomApi::class, GameRoomApi::class, GameApi::class])
class HttpClientsConfig {

    @Bean
    fun userIdPropagationConfigurer(interceptor: UserIdPropagationInterceptor) =
        RestClientHttpServiceGroupConfigurer { groups ->
            groups.forEachClient { _, builder ->
                builder.requestInterceptor(interceptor)
            }
        }
}
