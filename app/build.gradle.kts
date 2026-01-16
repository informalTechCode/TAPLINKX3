import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.TapLinkX3.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.TapLinkX3.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        noCompress += "onnx"
        noCompress += "txt" // for tokens.txt
    }



    buildTypes {
        debug {
            applicationIdSuffix = ".debug"  // Add this line
            // Append .debug to the package name for debug builds
            resValue("string", "app_name", "TapLink_dev")
        }

        release {
            resValue("string", "app_name", "TapLink X3")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.webkit)

    // Google
    implementation(libs.material)
    implementation(libs.core)
    implementation(libs.gson)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Local AARs
    implementation(files("libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar"))
    implementation(fileTree("libs"))

    // OkHttp for API requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
