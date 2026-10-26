plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework:spring-web")
    api("jakarta.validation:jakarta.validation-api")
    api(libs.swaggerAnnotations)
    // Полиморфные DTO (oneOf по дискриминатору): @JsonTypeInfo/@JsonSubTypes. Пакет аннотаций общий для Jackson 2 и 3.
    api("com.fasterxml.jackson.core:jackson-annotations")
    // @NamedInterface: пакет site.qgames.game.api открыт другим модулям (Spring Modulith).
    api("org.springframework.modulith:spring-modulith-api")
    // Тип ответа SSE-ручек (ResponseBodyEmitter); в рантайме его даёт Spring MVC приложения.
    compileOnly("org.springframework:spring-webmvc")
}
