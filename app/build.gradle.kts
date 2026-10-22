plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.personal.pocketagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.personal.pocketagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        resValue("string", "app_name", "PocketAgent")

        val cloudApiKey = System.getenv("CLOUD_API_KEY") ?: ""
        buildConfigField("String", "CLOUD_API_KEY", "\"$cloudApiKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Core AndroidX, Material Components & Activity Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // Jetpack Compose BOM & UI
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // On-Device Offline LLM (MediaPipe GenAI)
    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    // Networking & Serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Room Database (Offline Notes, FTS4 Search & Custom Macros)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Jetpack Glance (Home Screen AppWidget)
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
