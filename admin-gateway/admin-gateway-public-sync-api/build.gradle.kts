plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework:spring-web")
    api("jakarta.validation:jakarta.validation-api")
    api(libs.swaggerAnnotations)
    // Простое проксирование: ручки отдают и принимают DTO контрактов сервисов как есть, без собственных дублей.
    api(project(":admin-auth:admin-auth-admin-gateway-sync-api"))
}
