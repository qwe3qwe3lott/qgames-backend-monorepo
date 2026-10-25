// Базовая конвенция для любого Kotlin-модуля: библиотеки, модули приложения, их -api и само приложение app.
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.spring")
}

group = "site.qgames"

// JDK 25 или новее: компилируем тем JDK, на котором запущен Gradle, а байткод фиксируем на 25
// (в Docker-образе рантайм — JRE 25). Toolchain не используем, чтобы не требовать ровно JDK 25.
require(JavaVersion.current() >= JavaVersion.VERSION_25) { "Нужен JDK 25+, сейчас ${JavaVersion.current()}" }

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.add("-Xjsr305=strict")
        // Компилируем против API JDK 25, а не JDK хоста: как options.release у javac.
        freeCompilerArgs.add("-Xjdk-release=25")
    }
}

val libs = versionCatalogs.named("libs")

dependencies {
    // Версии зависимостей берутся из Spring Boot BOM (и BOM Spring Modulith и Exposed, которых в нём нет) для всех модулей.
    "api"(platform(SpringBootPlugin.BOM_COORDINATES))
    "testImplementation"(platform(SpringBootPlugin.BOM_COORDINATES))
    "api"(platform(libs.findLibrary("springModulithBom").get()))
    "testImplementation"(platform(libs.findLibrary("springModulithBom").get()))
    "api"(platform(libs.findLibrary("exposedBom").get()))
    "testImplementation"(platform(libs.findLibrary("exposedBom").get()))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
