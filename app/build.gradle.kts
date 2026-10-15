plugins {
    alias(libs.plugins.android.application)
}

val umbraVersionName = providers.gradleProperty("umbraVersionName").orElse("3.2.0").get()
val umbraVersionCode = providers.gradleProperty("umbraVersionCode").orElse("30200").get().toInt()

android {
    namespace = "com.umbra.hooks"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.umbra.hooks"
        minSdk = 26
        targetSdk = 37
        versionCode = umbraVersionCode
        versionName = umbraVersionName

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    androidResources {
        localeFilters += "en"
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
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Lifecycle, ViewModel, and Activity KTX for Clean Architecture
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-ktx:1.9.3")

    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
}

// ACS_SIGNING_CONFIG_START
val keystorePropertiesFile = file("keystore.properties")
val acsProps = if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.readLines()
        .filter { '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
} else emptyMap()

android {
    signingConfigs {
        maybeCreate("release").apply {
            val sf = acsProps["storeFile"]
            val sp = acsProps["storePassword"]
            val ka = acsProps["keyAlias"]
            val kp = acsProps["keyPassword"]
            if (!sf.isNullOrBlank()) storeFile = file(sf)
            if (!sp.isNullOrBlank()) storePassword = sp
            if (!ka.isNullOrBlank()) keyAlias = ka
            if (!kp.isNullOrBlank()) keyPassword = kp
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
// ACS_SIGNING_CONFIG_END
