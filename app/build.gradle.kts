plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.umbra.hooks"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.umbra.hooks"
        minSdk = 26
        targetSdk = 34
        
        versionCode = 20
        versionName = "2.2.0"

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        viewBinding = true
        dataBinding = false
    }

    packaging {
        resources {
            excludes += "META-INF/**"
            excludes += "**/attach_hotagent.dll"
            excludes += "LICENSE.txt"
        }
        jniLibs {
            useLegacyPackaging = false 
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
   
    compileOnly("de.robv.android.xposed:api:82")

    // DexKit (Managed via Version Catalog)
    implementation(libs.dexkit)
}
