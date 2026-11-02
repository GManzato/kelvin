import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "be.apexlab.kelvin"
    compileSdk = 35

    defaultConfig {
        applicationId = "be.apexlab.kelvin"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.6"
        manifestPlaceholders["appLabel"] = "Kelvin"
    }

    // Release key lives outside the sources: android/keystore.properties + android/keystore/ (both git-ignored).
    val keystoreFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreFile.exists()) create("release") {
            val p = Properties().apply { keystoreFile.inputStream().use { load(it) } }
            storeFile = rootProject.file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // separate app id so dev builds and the shared release can be installed side by side
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Kelvin dev"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // BOM 2025.04 = Compose 1.8, the last line that builds against compileSdk 35.
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.10.1")
    testImplementation("junit:junit:4.13.2")
}
