plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "de.pokemoncarddetector"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.pokemoncarddetector"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
        vectorDrawables { useSupportLibrary = true }
        // Nur Handy-Prozessoren (ARM) – halbiert die APK-Groesse (ML-Kit-Bibliotheken)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // Fester Schluessel des Projekts, damit neue APKs (auch aus GitHub Actions) als Update
        // installiert werden koennen und die Sammlung erhalten bleibt. Fuer einen Play-Store-Release
        // einen eigenen, geheimen Schluessel per Umgebungsvariablen verwenden.
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "../keystore/karten-scanner.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "kartenscanner"
            keyAlias = System.getenv("KEY_ALIAS") ?: "karten-scanner"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "kartenscanner"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }
    androidResources {
        // Katalog wird beim ersten Start gelesen – unkomprimiert geht schneller.
        noCompress += "json"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Datenbank
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Kamera + Texterkennung (laeuft komplett auf dem Geraet)
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    // Preise taeglich im Hintergrund aktualisieren
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Netzwerk + Bilder
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
}
