plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.yeapguy.volumescroll"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.yeapguy.volumescroll"
        // Patched: was 29. Android 9 (API 28) supports every API this app uses;
        // key-event filtering via AccessibilityService was introduced in API 28,
        // which makes Pie the oldest Android version the app can run on.
        minSdk = 28
        targetSdk = 36
        versionCode = 20
        versionName = "1.9.2-pie"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}