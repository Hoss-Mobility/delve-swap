plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("localPlatformPath").orNull
        if (!localPath.isNullOrBlank()) {
            local(localPath)
        } else {
            create(
                providers.gradleProperty("platformType").get(),
                providers.gradleProperty("platformVersion").get(),
            )
        }
        bundledPlugin("org.jetbrains.plugins.go")

        // Pin the IntelliJ Plugin Verifier CLI. The version bundled with the
        // Gradle plugin crashes with a ClosedFileSystemException when verifying
        // against multiple GoLand builds in parallel; newer verifier releases
        // fix that concurrency bug. See CI run 26505542120.
        pluginVerifier(providers.gradleProperty("pluginVerifierVersion").get())
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Capped to next major (verifyPlugin in CI checks 261 + 262). Bump as new GoLand
            // releases ship — leaving it open would silently crash users on a future
            // incompatible GoLand instead of disabling the plugin.
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.publishPlugin {
    // The release workflow runs publishPlugin in a separate Gradle invocation
    // where signPlugin is skipped/UP-TO-DATE (didWork=false), which would make
    // the default convention fall back to the UNSIGNED buildPlugin zip. Pin to
    // the signed artifact unconditionally.
    archiveFile = tasks.signPlugin.flatMap { it.signedArchiveFile }
}

// Set skipSearchableOptions=true locally to skip the IDE-launched indexing step.
tasks.named("buildSearchableOptions") {
    enabled = providers.gradleProperty("skipSearchableOptions").orNull?.toBoolean() != true
}
