plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Leer API keys desde secrets.properties (no se sube a GitHub)
val secretsFile = rootProject.file("secrets.properties")
val secrets = if (secretsFile.exists()) {
    java.util.Properties().apply { load(secretsFile.inputStream()) }
} else {
    java.util.Properties().apply {
        // Valores vacíos para CI - se inyectan via GitHub Secrets
        setProperty("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") ?: "")
        setProperty("GROQ_API_KEY", System.getenv("GROQ_API_KEY") ?: "")
        setProperty("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY") ?: "")
    }
}

android {
    namespace = "com.nubiaagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nubiaagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "dayana-v4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Inyectar API keys en BuildConfig
        buildConfigField("String", "GEMINI_API_KEY", "\"${secrets.getProperty("GEMINI_API_KEY", "")}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${secrets.getProperty("GROQ_API_KEY", "")}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${secrets.getProperty("OPENAI_API_KEY", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Optimizaciones para Unisoc T8300 / NeoTurbo
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt"
            )
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Vosk - Speech Recognition 100% Offline
    implementation("com.alphacephei:vosk-android:0.3.47")

    // TensorFlow Lite - Wake Word / On-device ML
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-task-audio:0.4.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // JSON Parsing
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.json:json:20231013")

    // Location Services
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Work Manager para tareas en segundo plano
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ==================== CAPA COGNITIVA ====================

    // Room - Base de datos local para memoria
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore para preferencias
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ONNX Runtime - Para inferencia de modelos de embeddings
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")

    // ==================== CAPA DE EJECUCIÓN ====================

    // Biometric - Autenticación por huella/rostro
    implementation("androidx.biometric:biometric:1.1.0")

    // Security - EncryptedSharedPreferences y Keystore
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Camera2 - Control de cámara (Neovision AI bridge)
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")

    // WebView - Canvas Controller
    implementation("androidx.webkit:webkit:1.9.0")

    // MediaRouter - Z-SmartCast bridge
    implementation("androidx.mediarouter:mediarouter:1.6.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
}
