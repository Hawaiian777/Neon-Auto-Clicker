import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.neon.autoclicker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.neon.autoclicker"
        minSdk = 24
        targetSdk = 35
        // Version shown in the app ("Version 1.3.0"): set once for Android and Windows in gradle.properties.
        versionName = providers.gradleProperty("neon.versionName").get()
        // Build number shown under it: set automatically from the build date and hour (yyMMddHH),
        // so every new build gets a higher number without anyone editing it (same rule on Windows).
        versionCode = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHH")).toInt()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core")) // shared with the Windows app
}
