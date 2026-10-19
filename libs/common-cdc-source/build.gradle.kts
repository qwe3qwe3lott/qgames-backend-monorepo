plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("com.google.protobuf:protobuf-java")
    api("org.springframework:spring-jdbc")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
}
