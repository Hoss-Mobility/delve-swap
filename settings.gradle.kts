pluginManagement {
    repositories {
        // Local mirror for offline development; harmless on CI (always falls through).
        providers.gradleProperty("localPluginRepo").orNull?.let { maven(it) }
        gradlePluginPortal()
        maven("https://packages.jetbrains.com/repositories/intellij-platform-gradle-plugin/")
        mavenCentral()
    }
}

// Auto-provisions the JDK 21 toolchain the IntelliJ Platform plugin requires, so contributors
// don't have to install a matching JDK manually. CI's actions/setup-java provides JDK 21
// explicitly and this resolver is a no-op there.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "delveswap"
