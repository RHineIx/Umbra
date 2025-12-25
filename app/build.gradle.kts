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
        versionCode = 4
        versionName = "2.2-Fix"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // إزالة abiFilters للسماح بتضمين كل المعماريات تلقائياً لتجنب الأخطاء
        // ndk { abiFilters.add("arm64-v8a") } 
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    
    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        viewBinding = true
        dataBinding = true
    }

    // التغيير الجذري: منع ضغط المكتبات لتسهيل التحميل المباشر
    packaging {
        jniLibs {
            useLegacyPackaging = false 
            excludes += "META-INF/**"
        }
    }
}

dependencies {
    // Force Core-KTX version
    val coreVersion = "1.13.1"
    implementation("androidx.core:core-ktx:$coreVersion")
    
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
   
    compileOnly("de.robv.android.xposed:api:82")

    // UPDATE: DexKit 2.0.6 (Latest stable for this usage)
    implementation("org.luckypray:dexkit:2.0.6")
}

configurations.all {
    resolutionStrategy {
        force("androidx.core:core-ktx:1.13.1")
        force("androidx.core:core:1.13.1")
    }
}