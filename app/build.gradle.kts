plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hcode91.toksaver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hcode91.toksaver"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Fixed keystore so every CI build can install over the previous one.
    signingConfigs {
        create("fixed") {
            storeFile = file("toksaver.keystore")
            storePassword = "toksaver"
            keyAlias = "toksaver"
            keyPassword = "toksaver"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    // yt-dlp runs Python from nativeLibraryDir, so native libs must be extracted.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    // MediaPipe memory-maps the model, so it must stay uncompressed.
    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
}
