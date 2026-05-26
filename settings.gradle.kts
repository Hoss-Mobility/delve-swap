pluginManagement {
    repositories {
        // Local mirror for offline development; harmless on CI (always falls through).
        providers.gradleProperty("localPluginRepo").orNull?.let { maven(it) }
        maven("https://packages.jetbrains.com/repositories/intellij-platform-gradle-plugin/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "delve-per-config"
