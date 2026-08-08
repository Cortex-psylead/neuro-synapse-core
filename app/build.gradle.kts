plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-parcelize")
}

android {
    namespace = "com.neurosynapse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.neurosynapse.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndkVersion = "28.0.12433566" // Requerido para alineación 16KB en libc++_shared

        // OPTIMIZACIÓN: Solo compilar para arquitecturas de celulares reales
        // Esto reduce drásticamente el trabajo del empaquetador incremental
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    packaging {
        jniLibs {
            // Regla de oro: No tocar las librerías nativas (evita el error de strip)
            keepDebugSymbols.add("**/lib*.so")
            
            // Resolver duplicados entre OpenCV y SQLCipher
            pickFirsts.add("**/libc++_shared.so")
            pickFirsts.add("**/libimage_processing_util_jni.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-viewbinding")

    implementation("androidx.room:room-runtime:2.7.0-alpha13")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core:1.13.1")
    constraints {
        implementation("androidx.core:core-ktx:1.13.1")
        implementation("androidx.core:core:1.13.1")
    }
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")

    val cameraVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("com.google.guava:guava:31.1-android")
    
    // IA: llama.cpp Direct Binding (GGUF Support) - Maven Central
    implementation("org.codeshipping:llama-kotlin-android:0.1.7")
}
