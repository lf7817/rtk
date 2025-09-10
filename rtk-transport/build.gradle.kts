plugins {
    id("java-library")
    id("maven-publish")
    alias(libs.plugins.jetbrains.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    // Kotlin 标准库
    implementation(kotlin("stdlib"))

    // Kotlin 协程核心库（包含 Flow 支持）
    implementation(libs.kotlinx.coroutines.core)

    // 测试库
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(project(":nmea"))
}

