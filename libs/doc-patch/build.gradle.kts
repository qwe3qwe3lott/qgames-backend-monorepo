// Патчи JSON-документов: модель операции (метод, путь по ключам и {id}) и общий применитель. Чистый Kotlin, без Spring и IO.
plugins {
    id("qgames.kotlin-library")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
