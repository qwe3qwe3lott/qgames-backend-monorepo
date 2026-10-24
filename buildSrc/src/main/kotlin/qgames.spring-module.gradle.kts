// Конвенция для модуля приложения (user-auth, admin-auth, game): библиотека со Spring-кодом модуля, без
// @SpringBootApplication и bootJar. Модули запускает приложение app (qgames.spring-service).
plugins {
    id("qgames.kotlin-library")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "implementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "implementation"("org.springframework.boot:spring-boot-starter-validation")
    "implementation"("tools.jackson.module:jackson-module-kotlin")
    "implementation"("org.jetbrains.kotlin:kotlin-reflect")
    // Аннотации Swagger на контроллерах и @ApplicationModuleListener (события модулей).
    "implementation"(libs.findLibrary("swaggerAnnotations").get())
    "implementation"("org.springframework.modulith:spring-modulith-events-api")
}
