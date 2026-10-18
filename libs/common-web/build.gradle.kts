plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    // springdoc подключают сами сервисы (qgames.spring-service); здесь — только для OpenAPI-кастомизации.
    compileOnly(libs.springdocWebmvcUi)
}
