plugins {
    id("qgames.spring-service")
}

dependencies {
    implementation(project(":libs:common-exception"))
    implementation(project(":libs:common-web"))
    implementation(project(":libs:common-cdc-source"))
    implementation(project(":user-auth:user-auth-internal-async-api"))
    implementation(project(":user-auth:user-auth-user-gateway-sync-api"))

    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springframework.security:spring-security-crypto")
    implementation(libs.bouncycastle)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":libs:common-test"))
}
