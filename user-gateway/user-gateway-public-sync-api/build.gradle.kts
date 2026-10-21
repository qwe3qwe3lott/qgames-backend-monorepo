plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework:spring-web")
    api("jakarta.validation:jakarta.validation-api")
    api(libs.swaggerAnnotations)
    // Полиморфные DTO (oneOf по дискриминатору): @JsonTypeInfo/@JsonSubTypes. Пакет аннотаций общий для Jackson 2 и 3.
    api("com.fasterxml.jackson.core:jackson-annotations")
    // Простое проксирование: ручки отдают и принимают DTO контрактов сервисов как есть, без собственных дублей.
    api(project(":game:game-user-gateway-sync-api"))
    api(project(":user-auth:user-auth-user-gateway-sync-api"))
    // Тип ответа потоковой ручки (ResponseBodyEmitter); в рантайме его даёт сам gateway (Spring MVC).
    compileOnly("org.springframework:spring-webmvc")
}
