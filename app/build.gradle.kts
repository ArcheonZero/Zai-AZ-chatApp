import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ключ подписи лежит вне репозитория: ../keys/keystore.properties (см. keystore.properties.example).
// Запасной вариант — keystore.properties в корне проекта (не коммитить, он в .gitignore).
val keystorePropsFile = listOf(
    rootProject.file("../keys/keystore.properties"),
    rootProject.file("keystore.properties")
).firstOrNull { it.exists() }
val keystoreProps = Properties().apply {
    if (keystorePropsFile != null) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}
val hasSigningKey = keystorePropsFile != null

android {
    namespace = "ru.archeonzero.azchat"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.archeonzero.azchat"
        minSdk = 24
        targetSdk = 34
        versionCode = 20
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = File(keystorePropsFile!!.parentFile, keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasSigningKey) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
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
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Android Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Material Design 3
    implementation("com.google.android.material:material:1.12.0")

    // Layout & interaction
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
