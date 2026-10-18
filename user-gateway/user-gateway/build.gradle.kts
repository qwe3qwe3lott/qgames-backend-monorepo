plugins {
    id("qgames.spring-service")
}

dependencies {
    implementation(project(":libs:common-web"))
    implementation(project(":user-gateway:user-gateway-public-sync-api"))
    implementation(project(":user-auth:user-auth-user-gateway-sync-api"))

    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")

    testImplementation(project(":libs:common-test"))
}
