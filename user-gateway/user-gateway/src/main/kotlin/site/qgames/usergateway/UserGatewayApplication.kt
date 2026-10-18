package site.qgames.usergateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class UserGatewayApplication

fun main(args: Array<String>) {
    runApplication<UserGatewayApplication>(*args)
}
