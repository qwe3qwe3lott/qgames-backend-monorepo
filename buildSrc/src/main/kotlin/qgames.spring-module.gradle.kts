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
    // Аннотации Swagger на контроллерах.
    "implementation"(libs.findLibrary("swaggerAnnotations").get())
    // Доступ к данным — Exposed DSL в транзакциях Spring; менеджер транзакций Exposed подключает app.
    "implementation"(libs.findLibrary("exposedCore").get())
    "implementation"(libs.findLibrary("exposedJdbc").get())
    "implementation"(libs.findLibrary("exposedJavaTime").get())
    "implementation"(libs.findLibrary("exposedJson").get())
    "implementation"("org.springframework:spring-tx")
}
