package site.qgames.adminauth

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class AdminAuthApplication

fun main(args: Array<String>) {
    runApplication<AdminAuthApplication>(*args)
}
