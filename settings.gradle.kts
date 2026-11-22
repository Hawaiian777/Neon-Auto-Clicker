pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // Downloads the Java 21 needed to build the Windows app if this PC doesn't have one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "NeonAutoClicker"
include(":core")      // shared logic (Android + Windows)
include(":desktop")   // Windows
// "Build Windows App.bat" passes -Pneon.desktopOnly=true so the Windows build doesn't need the Android SDK.
if (providers.gradleProperty("neon.desktopOnly").orNull != "true") {
    include(":app")   // Android
}
