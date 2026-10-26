plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api(project(":libs:common-exception"))
    api("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    // Пользователь запроса — из SecurityContext (аутентификацию делает Security-цепочка приложения).
    implementation("org.springframework.security:spring-security-core")
    // springdoc подключает приложение (qgames.spring-service); здесь — только для OpenAPI-кастомизации.
    compileOnly(libs.springdocWebmvcUi)
    // Признак required для OpenAPI берётся из nullability параметров конструктора Kotlin-DTO.
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}
