plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.photographercamera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.photographercamera"
        minSdk = 26          // adaptive-icon launcher requires >=26; GLES 3.0 (18) & Camera2 (21) both satisfied
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-alpha"
        vectorDrawables { useSupportLibrary = true }
        // Camera permission is normal-tier at install; requested at runtime in CameraEngine.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // The 15-layer GLSL chain + baked LUTs must survive R8.
            // AGP 8.x types debugSymbolLevel as a String ("SYMBOL_TABLE" | "FULL" |
            // null); the enum form only exists in newer AGP majors.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui:1.7.2")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.2")
    // CameraX 1.6.2 — adds stable RAW/DNG capture (OUTPUT_FORMAT_RAW_JPEG).
    // Required for the RAW -> RAW ISP -> recipe pipeline (新建空白.txt route).
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Coil — Compose image loading (MediaStore URIs, downsampling, EXIF, caching)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // JSON (Schema + range validation, Import/Export) — pure-JVM, no AI runtime.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.2")
}
