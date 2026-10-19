plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework:spring-web")
    api("org.testcontainers:testcontainers-junit-jupiter")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-redpanda")
    api(libs.wiremock)
    // Kafka-клиент есть у сервисов-потребителей CDC; остальным модулям он в тестах не нужен.
    compileOnly("org.apache.kafka:kafka-clients")
}
