// Root project build file. Android SDK / NDK versions are pinned; the renderer
// requires OpenGL ES 3.0 (GLSL #version 300 es) and therefore minSdk 18+.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0: the Compose compiler ships as a Kotlin Gradle plugin; the legacy
    // composeOptions.kotlinCompilerExtensionVersion route is deprecated and must not
    // be combined with this plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
