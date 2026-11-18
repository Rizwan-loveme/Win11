// Top-level build file.
// Versions follow the BlackBox engine (AGP 8.x, Kotlin 1.9, JDK 21, NDK 29): the engine's
// Groovy/ndkBuild modules are not compatible with AGP 9, so this project stays on AGP 8.13.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
}

// Read by the engine's Groovy modules as rootProject.ext.* (compileSdkVersion, minSdk, ndkVer)
extra["compileSdkVersion"] = 37
extra["targetSdkVersion"] = 37
extra["minSdk"] = 24
extra["ndkVer"] = "29.0.13846066"
