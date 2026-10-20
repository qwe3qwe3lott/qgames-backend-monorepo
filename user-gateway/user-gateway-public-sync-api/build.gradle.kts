plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework:spring-web")
    api("jakarta.validation:jakarta.validation-api")
    api(libs.swaggerAnnotations)
    // Полиморфные DTO (oneOf по дискриминатору): @JsonTypeInfo/@JsonSubTypes. Пакет аннотаций общий для Jackson 2 и 3.
    api("com.fasterxml.jackson.core:jackson-annotations")
}
