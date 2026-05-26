pluginManagement {
    repositories {
        maven("https://packages.jetbrains.com/repositories/intellij-platform-gradle-plugin/")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "delve-per-config"
