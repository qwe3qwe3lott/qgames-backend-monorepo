plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework:spring-web")
    api("jakarta.validation:jakarta.validation-api")
    api(libs.swaggerAnnotations)
}
