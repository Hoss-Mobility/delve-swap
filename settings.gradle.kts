pluginManagement {
    repositories {
        // Local mirror for offline development; harmless on CI (always falls through).
        providers.gradleProperty("localPluginRepo").orNull?.let { maven(it) }
        gradlePluginPortal()
        maven("https://packages.jetbrains.com/repositories/intellij-platform-gradle-plugin/")
        mavenCentral()
    }
}

rootProject.name = "delveswap"
