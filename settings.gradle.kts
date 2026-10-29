dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

rootProject.name = "qgames-backend-monorepo"

// Общие инфраструктурные библиотеки
include(":libs:common-exception")
include(":libs:common-web")
include(":libs:common-test")
include(":libs:doc-patch")

// Правила игр: чистый Kotlin без инфраструктуры (см. CLAUDE.md)
include(":games:game-core")
include(":games:nothing-game")
include(":games:checkers-game")

// Модули приложения (Spring Modulith): реализация модуля и его публичный контракт <m>-api.
include(":user-auth:user-auth")
include(":user-auth:user-auth-api")

// Комнаты и партии всех игр.
include(":game:game")
include(":game:game-api")

// Админ-панель: аутентификация администраторов.
include(":admin-auth:admin-auth")
include(":admin-auth:admin-auth-api")

// Приложение: собирает модули; Security, OpenAPI, конфигурация и миграции.
include(":app")
