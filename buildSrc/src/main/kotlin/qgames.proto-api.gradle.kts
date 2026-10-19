// Конвенция для модулей *-internal-async-api (и позже *-internal-sync-api): Protobuf-контракты.
// Генерируются Java-классы и Kotlin DSL-обёртки; gRPC-стабы подключим вместе с первым gRPC-методом.
plugins {
    id("qgames.kotlin-library")
    id("com.google.protobuf")
}

val libs = versionCatalogs.named("libs")
val protocVersion = libs.findVersion("protoc").get().requiredVersion

dependencies {
    "api"("com.google.protobuf:protobuf-java")
    "api"("com.google.protobuf:protobuf-kotlin")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protocVersion"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("kotlin")
            }
        }
    }
}
