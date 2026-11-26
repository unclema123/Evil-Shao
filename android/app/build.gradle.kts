plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unclema123.evilshao"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.unclema123.evilshao"
        minSdk = 24
        targetSdk = 35
        versionCode = 16
        versionName = "2.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug 签名，保证直接能装到模拟器/手机上
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 不依赖 AndroidX，界面全部用 framework 原生控件，构建更快、更不容易挂
}