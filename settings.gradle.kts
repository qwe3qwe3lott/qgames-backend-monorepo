dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "qgames-backend-monorepo"

// Общие инфраструктурные библиотеки
include(":libs:common-exception")
include(":libs:common-web")
include(":libs:common-test")
include(":libs:common-cdc-source")
include(":libs:common-cdc-consumer")

// Правила игр: чистый Kotlin без инфраструктуры (см. CLAUDE.md)
include(":games:game-api")
include(":games:nothing-game")

// Сервисы. Модули <svc>-*-sync-api / -internal-async-api заводятся только когда у них появляется содержимое.
include(":user-auth:user-auth")
include(":user-auth:user-auth-user-gateway-sync-api")
include(":user-auth:user-auth-internal-async-api")

include(":user-gateway:user-gateway")
include(":user-gateway:user-gateway-public-sync-api")

// Комнаты и партии всех игр.
include(":game:game")
include(":game:game-user-gateway-sync-api")
