import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.20"
    id("com.gradleup.shadow") version "8.3.4"
    application
}

group = "io.github.iprodigy"
version = "1.1.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(group = "ch.qos.logback", name = "logback-classic", version = "1.5.11")
    implementation(group = "com.github.twitch4j", name = "twitch4j", version = "1.22.0") {
        // temporary due to https://github.com/twitch4j/twitch4j/pull/1070
        exclude(group = "com.github.twitch4j", module = "twitch4j-eventsub-common")
    }
    implementation(files("libs/twitch4j-eventsub-common.jar"))
    implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version = "1.7.3")
    implementation(platform("io.github.xanthic.cache:cache-bom:0.6.2"))
    implementation(group = "io.github.xanthic.cache", name = "cache-provider-caffeine3")
    implementation(group = "io.github.xanthic.cache", name = "cache-kotlin")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

application {
    mainClass.set("io.github.iprodigy.MainKt")
}
