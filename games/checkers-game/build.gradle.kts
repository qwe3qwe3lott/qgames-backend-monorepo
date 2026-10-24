// Шашки: правила (варианты — по флагам правил хода), чистый Kotlin.
plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api(project(":games:games-api"))

    // Чистые функции правил проверяются без Spring и контейнеров.
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    // Перевод значений событий в JSON-дерево (SessionCodec) — как в сервисе game.
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
