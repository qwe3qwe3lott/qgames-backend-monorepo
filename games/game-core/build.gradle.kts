// Core игр: модель игры (GameRules, SessionEngine) и общие механики партии; чистый Kotlin, без Spring, БД и IO.
plugins {
    id("qgames.kotlin-library")
}

dependencies {
    // События партии — патчи документа: модель операции и общий применитель (DocumentPatch).
    api(project(":libs:doc-patch"))
}
