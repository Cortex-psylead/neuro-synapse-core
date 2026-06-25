// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE — :data/build.gradle.kts
//
// Módulo de infraestructura Android. Implementa los puertos definidos en
// :domain y gestiona persistencia, seguridad y motores de IA.
//
// DEPENDENCIAS DECLARADAS:
//   Sprint 1 (este archivo): Room + SQLCipher + AndroidKeyStore + Coroutines
//   Sprint 2: Play Integrity API + biometric
//   Sprint 3-4: Whisper.cpp JNI + OpenCV
//   Sprint 5: ONNX Runtime (se añadirán en sus respectivos sprints)
//
// PRINCIPIO: :data conoce :domain (implementa sus contratos).
//            :domain NO conoce :data (sin imports de esta dirección).
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // KSP para Room — más rápido que KAPT y compatible con Kotlin 2.x
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.neurosynapse.app.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26                 // API 26 (Android 8.0): mínimo para AndroidKeyStore HMAC-SHA256
        targetSdk = 35

        // ABIs: sólo arm64-v8a en el MVP (ADR-007)
        // x86/x86_64 se excluyen para reducir el APK y el footprint de binarios nativos
        ndk {
            abiFilters += setOf("arm64-v8a")
        }

        // Room: directorio de exportación de schemas para documentar migrations
        // El schema JSON se versiona en Git para auditoría de cambios de BD
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
            arg("room.expandProjection", "true")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // consumer-rules.pro protege los enum names del Merkle hash (ADR-003)
            consumerProguardFiles("consumer-rules.pro")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    // Habilitar CMake para los binarios JNI nativos (Sprint 3: Whisper + Praat)
    // Comentado hasta Sprint 3 — descommentar cuando se añadan los fuentes C++
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }
}

dependencies {

    // ── Módulo de dominio (contratos a implementar) ──────────────────────────
    implementation(project(":domain"))

    // ── Kotlin ───────────────────────────────────────────────────────────────
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── Room + KSP ────────────────────────────────────────────────────────────
    // Room 2.6.x: soporte para Flow, PagingSource y KSP
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")        // Extensiones coroutines
    ksp("androidx.room:room-compiler:$roomVersion")              // KSP genera el código Room
    // Room testing (tests unitarios con BD en memoria — sin SQLCipher)
    testImplementation("androidx.room:room-testing:$roomVersion")

    // ── SQLCipher (cifrado AES-256 de páginas SQLite) ─────────────────────────
    // net.zetetic es el mantenedor oficial de SQLCipher for Android
    // 4.5.x: compatible con SQLite 3.39+, soporte WAL verificado
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    // Dependencia de SQLite nativo que SQLCipher necesita
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // ── Android Security: Keystore ────────────────────────────────────────────
    // Incluido en el SDK de Android — no requiere dependencia externa
    // Se usa via java.security.KeyStore con proveedor "AndroidKeyStore"

    // ── AndroidX Core ────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")

    // ── Sprint 2 — activado ───────────────────────────────────────────────────
    // Play Integrity API para attestSessionIntegrity()
    implementation("com.google.android.play:integrity:1.3.0")
    // Biometric para autenticación BIOMETRIC_STRONG del terapeuta
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    // Coroutines Play tasks adapter (para .await() sobre Task<T>)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ── Sprint 3-5 (comentado hasta su implementación) ───────────────────────
    // ONNX Runtime Mobile para los motores SLM y YOLO
    // implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")
    // CameraX para captura de imágenes proyectivas
    // implementation("androidx.camera:camera-camera2:1.3.3")
    // implementation("androidx.camera:camera-lifecycle:1.3.3")

    // ── Testing ───────────────────────────────────────────────────────────────
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.11")

    // Tests instrumentados (androidTest) — requieren emulador o dispositivo
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
