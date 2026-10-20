plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework:spring-web")
    api("jakarta.validation:jakarta.validation-api")
    api(libs.swaggerAnnotations)
    // Полиморфные DTO (oneOf по дискриминатору): @JsonTypeInfo/@JsonSubTypes. Пакет аннотаций общий для Jackson 2 и 3.
    api("com.fasterxml.jackson.core:jackson-annotations")
    // Простое проксирование: ручки комнат отдают DTO контракта game как есть, без собственных дублей.
    api(project(":game:game-user-gateway-sync-api"))
    // Тип ответа потоковой ручки (ResponseBodyEmitter); в рантайме его даёт сам gateway (Spring MVC).
    compileOnly("org.springframework:spring-webmvc")
}
