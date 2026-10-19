plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-kafka")
    api("com.google.protobuf:protobuf-java")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
}
