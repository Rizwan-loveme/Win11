pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // এই লাইনটি রিপোজিটরি কনফ্লিক্ট দূর করবে
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://www.jitpack.io") }   // FreeReflection (used by the clone engine)
    }
}

rootProject.name = "Win11"
include(":app")
include(":Bcore")
include(":black-reflection")
include(":compiler")
