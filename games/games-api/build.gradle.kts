// SPI правил игры: чистый Kotlin, без Spring, БД и IO.
plugins {
    id("qgames.kotlin-library")
}

dependencies {
    // Общий применитель событий (SessionPatch) проверяется без Spring и контейнеров.
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
