plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

android {
    namespace = "mx.spen.lab"
    compileSdk = 36

    defaultConfig {
        applicationId = "mx.spen.lab"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    buildTypes {
        debug {
            // Installs beside the original diagnostic APK during comparison in the Galaxy.
            applicationIdSuffix = ".preview"
            manifestPlaceholders["appLabel"] = "@string/app_name_preview"
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    // This release first includes the correction for distorted finished strokes.
    val inkVersion = "1.1.0-alpha04"
    implementation("androidx.ink:ink-authoring:$inkVersion")
    implementation("androidx.ink:ink-brush:$inkVersion")
    implementation("androidx.ink:ink-geometry:$inkVersion")
    implementation("androidx.ink:ink-nativeloader:$inkVersion")
    implementation("androidx.ink:ink-rendering:$inkVersion")
    implementation("androidx.ink:ink-strokes:$inkVersion")
    implementation("androidx.input:input-motionprediction:1.0.0")
    testImplementation("junit:junit:4.13.2")
}
