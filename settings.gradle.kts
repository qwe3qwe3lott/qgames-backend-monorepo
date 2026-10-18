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
include(":libs:common-web")
include(":libs:common-test")

// Сервисы. Модули <svc>-user-gateway-sync-api / -internal-sync-api / -internal-async-api
// заводятся только когда у них появляется содержимое (см. CLAUDE.md).
include(":user-auth:user-auth")
include(":user-auth:user-auth-user-gateway-sync-api")

include(":user-gateway:user-gateway")
include(":user-gateway:user-gateway-public-sync-api")

// Комнаты и партии всех игр. Правила игр — модули games/* (см. CLAUDE.md).
include(":game:game")
