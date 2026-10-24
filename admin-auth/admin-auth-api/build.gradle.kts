plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework:spring-web")
    api("jakarta.validation:jakarta.validation-api")
    api(libs.swaggerAnnotations)
    // @NamedInterface: пакет site.qgames.adminauth.api открыт другим модулям (Spring Modulith).
    api("org.springframework.modulith:spring-modulith-api")
}
