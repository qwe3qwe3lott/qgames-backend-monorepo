plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework:spring-web")
    api("org.testcontainers:testcontainers-junit-jupiter")
    api("org.testcontainers:testcontainers-postgresql")
    // Внешние HTTP-системы (JWKS Google).
    api(libs.wiremock)
}
