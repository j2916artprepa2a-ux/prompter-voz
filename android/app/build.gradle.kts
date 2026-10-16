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
            storeFile = file("../keystore.jks")
            storePassword = "prompter123"
            keyAlias = "prompter"
            keyPassword = "prompter123"
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
