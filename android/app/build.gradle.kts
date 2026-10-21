plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "mx.prompter.voz"
    compileSdk = 34

    defaultConfig {
        applicationId = "mx.prompter.voz"
        minSdk = 26
        targetSdk = 34
        versionCode = run
        versionName = "1.0.$run"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Llave fija para que cada versión nueva se instale encima de la anterior
    signingConfigs {
        create("app") {
            // Preparado para migrar la firma (docs/SEGURIDAD-FIRMA.md): si existen estas variables
            // de entorno se usan; si no, se firma EXACTAMENTE igual que siempre.
            storeFile = file(System.getenv("TEXJAHC_KEYSTORE_PATH") ?: "../keystore.jks")
            storePassword = System.getenv("TEXJAHC_KEYSTORE_PASSWORD") ?: "prompter123"
            keyAlias = System.getenv("TEXJAHC_KEY_ALIAS") ?: "prompter"
            keyPassword = System.getenv("TEXJAHC_KEY_PASSWORD") ?: "prompter123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
        }
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
    }

    buildFeatures { buildConfig = true }

    // normal: sin Accesibilidad (Play Protect la deja instalar)
    // live: con Accesibilidad, para intentar seguir la voz mientras otra app graba (experimental)
    flavorDimensions += "tipo"
    productFlavors {
        create("normal") { dimension = "tipo" }
        create("live") { dimension = "tipo" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Voz integrada sin internet (la misma que usa la app web)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
