plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cuepoint"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cuepoint"
        // Wear OS 3.0+ (API 30+)
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Wear OS + Compose foundation
    implementation("androidx.wear.compose:compose-material:1.3.0")
    implementation("androidx.wear.compose:compose-foundation:1.3.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Icons
    implementation("androidx.compose.material:material-icons-extended:1.6.1")

    // HTTP client – lightweight and modern (alternative to Retrofit)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines – for async HTTP calls (≈ async/await in C#)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DataStore – token storage (≈ SecureStorage)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // OAuth via phone (Wear OS delegates login to the paired phone)
    implementation("androidx.wear:wear-phone-interactions:1.0.1")
}
