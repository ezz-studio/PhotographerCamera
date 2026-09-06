// Root project build file. Android SDK / NDK versions are pinned; the renderer
// requires OpenGL ES 3.0 (GLSL #version 300 es) and therefore minSdk 18+.
plugins {
    // AGP 9.4.0 (latest stable, Sep 2026) — kept on the LEGACY DSL via
    // android.newDsl=false / android.builtInKotlin=false (gradle.properties),
    // the officially supported conservative migration path for AGP 9.
    id("com.android.application") version "9.4.0" apply false
    // KGP 2.2.10 — the exact version AGP 9.4's built-in Kotlin runs on; fully
    // compatible with Gradle 9.x (2.0.x is not).
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    // Kotlin 2.x: the Compose compiler ships as a Kotlin Gradle plugin; the legacy
    // composeOptions.kotlinCompilerExtensionVersion route is deprecated and must not
    // be combined with this plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
