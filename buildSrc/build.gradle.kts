import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

// Та же схема, что в qgames.kotlin-library: без toolchain, на JDK 25+, байткод 25
// (иначе compileJava берёт версию текущего JDK и расходится с compileKotlin).
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.kotlinAllopenPlugin)
    implementation(libs.springBootGradlePlugin)
}
