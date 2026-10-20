plugins {
    id("qgames.spring-service")
}

dependencies {
    implementation(project(":libs:common-exception"))
    implementation(project(":libs:common-web"))
    implementation(project(":admin-gateway:admin-gateway-public-sync-api"))
    implementation(project(":admin-auth:admin-auth-admin-gateway-sync-api"))

    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")

    testImplementation(project(":libs:common-test"))
}
