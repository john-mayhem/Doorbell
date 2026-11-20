import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// The release key: android/keystore.properties on the PC (gitignored), environment variables on GitHub.
val keyProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun key(prop: String, env: String): String? = keyProps.getProperty(prop) ?: System.getenv(env)
val hasKey = key("storeFile", "KEYSTORE_FILE") != null

android {
    namespace = "com.mayhem.doorbell"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mayhem.doorbell"
        minSdk = 31
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.3"
    }
    signingConfigs {
        create("release") {
            if (hasKey) {
                storeFile = rootProject.file(key("storeFile", "KEYSTORE_FILE")!!)
                storePassword = key("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = key("keyAlias", "KEY_ALIAS") ?: "doorbell"
                keyPassword = key("keyPassword", "KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasKey) signingConfig = signingConfigs.getByName("release")
        }
        // Same key for builds made on the PC, so every build can update every other one.
        getByName("debug") {
            if (hasKey) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")  // a library pulls an old one in; lint stops release builds on it
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
