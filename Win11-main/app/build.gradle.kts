plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.srizwan.win11"
    compileSdk = 37
    ndkVersion = "29.0.13846066" // clone engine (Bcore) native code-এর জন্য

    defaultConfig {
        applicationId = "com.srizwan.win11"
        minSdk = 24
        targetSdk = 37
        versionCode = 101
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        // Bcore (clone engine) is compiled with Java 21
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }

    packaging {
        // The clone engine needs its native libs extracted to disk
        jniLibs { useLegacyPackaging = true }
    }
    
    // Compose প্রয়োজন নেই তাই এগুলো ফলস করে দেওয়া হলো
    buildFeatures { 
        compose = false 
        viewBinding = true
    }
}

dependencies {
    // Clone engine (virtual space) – used for the bKash / Nagad / WhatsApp clones
    implementation(project(":Bcore"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
	implementation("androidx.webkit:webkit:1.11.0")
    // এই লাইব্রেরিটি অত্যন্ত গুরুত্বপূর্ণ TabLayout ও BottomNav এর জন্য
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Messages lists (conversation list + chat) are RecyclerViews, so scrolling stays smooth
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
