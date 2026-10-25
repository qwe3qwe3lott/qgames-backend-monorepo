plugins {
    id("qgames.spring-service")
}

dependencies {
    implementation(project(":libs:common-exception"))
    implementation(project(":libs:common-web"))
    // Модули приложения. Между собой модули видят только *-api друг друга (проверяет ApplicationModules.verify()).
    implementation(project(":user-auth:user-auth"))
    implementation(project(":admin-auth:admin-auth"))
    implementation(project(":game:game"))

    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    // Exposed в транзакциях Spring: SpringTransactionManager поверх DataSource (Hikari) — один на приложение.
    implementation(libs.exposedSpringBoot4Starter)
    implementation(libs.exposedJdbc)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":libs:common-test"))
    // Тесты читают и меняют БД через Exposed DSL и таблицы модулей (без сырого SQL).
    testImplementation(libs.exposedJavaTime)
    testImplementation(libs.exposedJson)
    // ApplicationModules.verify(): границы модулей.
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    // Тесты game работают с моделями правил игр и сборкой состояний партий.
    testImplementation(project(":games:nothing-game"))
    testImplementation(project(":games:checkers-game"))
    testImplementation("com.nimbusds:nimbus-jose-jwt")
}
