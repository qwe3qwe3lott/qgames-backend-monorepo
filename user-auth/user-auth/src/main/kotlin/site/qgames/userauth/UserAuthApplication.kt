package site.qgames.userauth

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class UserAuthApplication

fun main(args: Array<String>) {
    runApplication<UserAuthApplication>(*args)
}
