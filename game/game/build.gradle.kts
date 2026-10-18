plugins {
    id("qgames.spring-service")
}

dependencies {
    implementation(project(":libs:common-web"))

    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    runtimeOnly("org.postgresql:postgresql")
}
