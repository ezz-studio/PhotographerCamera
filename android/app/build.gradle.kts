import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Single source of truth for the app version: defaultConfig.versionName AND the
// APK file name (PhotographerCamera-<version>.apk) both derive from this.
// Bump on every feature round: minor = feature batch, patch = fix-only round.
val APP_VERSION_NAME = "0.9.3"
val APP_VERSION_CODE = 33

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 0.8.0 临时重定向：闪退后旧 build/outputs/apk/debug 目录句柄被 WorkBuddy
// 预览进程锁死（delete/rename 均失败），构建目录整体切到 build2 绕开。
// 预览面板关闭后可删掉本行恢复默认 build/ 目录。
layout.buildDirectory = layout.projectDirectory.dir("build2")

android {
    namespace = "com.photographercamera"
    compileSdk = 36
    // 0.7.0 基座移植（PhotonCamera cpp/）：与上游一致的 NDK 版本
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.photographercamera"
        minSdk = 26          // adaptive-icon launcher requires >=26; GLES 3.0 (18) & Camera2 (21) both satisfied
        targetSdk = 34
        versionCode = APP_VERSION_CODE
        versionName = APP_VERSION_NAME
        vectorDrawables { useSupportLibrary = true }
        // Camera permission is normal-tier at install; requested at runtime in CameraEngine.
        // 0.7.0 起含 native 库（LibRaw/libjpeg-turbo/libultrahdr/mgc 去噪）：
        // 对齐上游仅 arm64-v8a，缩短 CMake 多 ABI 构建时间。
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 0.9.1: publish via the debug key (same signing identity as the
            // installed debug builds) so minified release APKs can sideload and
            // update in place. Swap to a dedicated release keystore before any
            // public store distribution.
            signingConfig = signingConfigs.getByName("debug")
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

    // 0.7.0：cpp/ native 模块（RAW MAX 去马赛克/DCP、JPEG 4:4:4、UltraHDR、EIS）随 APK 构建
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // 0.7.0：基座移植代码读取 BuildConfig.DEBUG（AGP 9 默认关闭，显式开启）
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// KGP 2.2+ replaces the deprecated kotlinOptions{} with the compilerOptions DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// APK naming rule: PhotographerCamera-<versionName>.apk (single APP-VERSION.apk
// per the project convention). Uses the stable AGP Variant API (androidComponents
// — the supported path under AGP 9, unlike the legacy applicationVariants hook).
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("PhotographerCamera-$APP_VERSION_NAME.apk")
            println("APK rename [${variant.name}] -> PhotographerCamera-$APP_VERSION_NAME.apk")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // androidx.lifecycle.compose.LocalLifecycleOwner lives here (the old
    // androidx.compose.ui.platform.LocalLifecycleOwner is deprecated)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
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
    // Room（移植的 GalleryManager/GalleryDatabase 用）
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    // DataStore（移植的 UserPreferencesRepository / LutManager 用）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // JSON (Schema + range validation, Import/Export) — pure-JVM, no AI runtime.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Gson — 0.7.0 基座移植（PhotonCamera Camera2Controller 闭包的状态序列化依赖）
    implementation("com.google.code.gson:gson:2.10.1")

    // 0.7.0 基座移植依赖（对齐上游 photon-camera）：
    // mediapipe tasks-vision = 眼睛对焦人脸检测 + RAW MAX TFLite 场景估计（自带 TFLite 运行时）
    implementation("com.google.mediapipe:tasks-vision:1.0.0")

    // TFLite（RAW MAX 场景曝光估计 / DNG 增益表生成，上游同版本）
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.5.0")

    // 0.7.5 移植 photon-camera CameraViewModel + Camera2Controller 闭包所需依赖（对齐上游）
    // appcompat / splashscreen（MainActivity / MyCameraApplication 入口）
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-splashscreen:1.2.0")
    // recyclerview（部分 UI 列表）
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // compose animation（上游 compose-bom 2025.12.01；本项目 compose 1.7.2，对齐版本）
    implementation("androidx.compose.animation:animation:1.7.2")
    implementation("androidx.compose.animation:animation-core:1.7.2")
    // telephoto（大图缩放查看，上游 0.18.0）
    implementation("me.saket.telephoto:zoomable-image-coil:0.18.0")
    // reorderable（拖拽排序列表，上游 2.4.3）
    implementation("sh.calvin.reorderable:reorderable:2.4.3")
    // media3（视频播放 / 导出 / 特效 / transformer，上游 1.10.0）
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")
    implementation("androidx.media3:media3-effect:1.10.0")
    implementation("androidx.media3:media3-transformer:1.10.0")
    // heifwriter（HEIC 导出，上游 1.1.0）
    implementation("androidx.heifwriter:heifwriter:1.1.0")
    // okhttp（OpenAIApiClient / 网络，上游 4.12.0）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Bugly（default flavor 崩溃上报；上游 latest.release）
    implementation("com.tencent.bugly:crashreport:latest.release")

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.2")
}
