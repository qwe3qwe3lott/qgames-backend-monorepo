plugins {
    id("qgames.spring-module")
}

dependencies {
    implementation(project(":libs:common-exception"))
    implementation(project(":libs:common-web"))
    api(project(":admin-auth:admin-auth-api"))

    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springframework.security:spring-security-crypto")
    // Argon2PasswordEncoder считает хеш через BouncyCastle.
    implementation(libs.bouncycastle)
}
