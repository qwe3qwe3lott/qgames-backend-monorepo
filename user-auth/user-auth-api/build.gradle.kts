plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework:spring-web")
    api("jakarta.validation:jakarta.validation-api")
    api(libs.swaggerAnnotations)
    // @NamedInterface: пакет site.qgames.userauth.api открыт другим модулям (Spring Modulith).
    api("org.springframework.modulith:spring-modulith-api")
    // Описание таблицы user для чтения другими модулями (UserProfileTable, Exposed).
    api("org.jetbrains.exposed:exposed-core")
}
