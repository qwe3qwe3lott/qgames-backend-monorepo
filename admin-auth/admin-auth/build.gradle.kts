plugins {
    id("qgames.spring-service")
}

dependencies {
    implementation(project(":libs:common-exception"))
    implementation(project(":libs:common-web"))
    implementation(project(":admin-auth:admin-auth-admin-gateway-sync-api"))

    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springframework.security:spring-security-crypto")
    // Argon2PasswordEncoder считает хеш через BouncyCastle.
    implementation(libs.bouncycastle)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":libs:common-test"))
}
