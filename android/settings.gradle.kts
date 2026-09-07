pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Tencent mirror first: aggregates google() + mavenCentral() and is
        // directly reachable from CN networks (mavenCentral TLS is flaky here,
        // which broke first-time release-variant dependency resolution).
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "PhotographerCamera"
include(":app")
