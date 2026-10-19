plugins {
    id("qgames.spring-service")
}

dependencies {
    implementation(project(":libs:common-exception"))
    implementation(project(":libs:common-web"))
    implementation(project(":libs:common-cdc-consumer"))
    implementation(project(":game:game-user-gateway-sync-api"))
    implementation(project(":user-auth:user-auth-internal-async-api"))
    // Правила игр. Новая игра — новый модуль games/* здесь и его бин в GamesConfig.
    implementation(project(":games:nothing-game"))

    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation(libs.shedlockSpring)
    implementation(libs.shedlockJdbc)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":libs:common-test"))
}
