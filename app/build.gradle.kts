plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.umbra.hooks"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.umbra.hooks"
        minSdk = 26
        targetSdk = 36

        versionCode = 20301
        versionName = "2.3.1"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("keystore.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (file("keystore.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = false
        dataBinding = false
    }

    packaging {
        resources {
            excludes += "**/attach_hotagent.dll"
            excludes += "LICENSE.txt"
        }
        jniLibs {
            // Required for Android 17: Native libraries must remain uncompressed
            useLegacyPackaging = true
        }
    }

    // CRITICAL: Prevent Lint from aborting the build for Xposed-specific API usage
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Modern Kotlin DSL for compiler options
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Standard AndroidX & UI
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Modern LibXposed API (Must be compileOnly to avoid ClassCastException)
    compileOnly(libs.libxposed.api)

    // LibXposed Service for IPC
    implementation(libs.libxposed.service)

    // DexKit 2.1.0
    implementation(libs.dexkit)
}