plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.neurosynapse.app.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":domain"))
    
    // OpenCV para Android
    api("com.quickbirdstudios:opencv:4.5.3.0")
    
    // IA: Removido MediaPipe (Migración a llama.cpp soberana)
    // api("com.google.mediapipe:tasks-genai:0.10.27")

    val roomVersion = "2.7.0-alpha13"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
