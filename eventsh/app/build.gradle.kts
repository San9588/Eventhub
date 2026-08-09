plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eventsh.app"
    compileSdk = 35

    buildFeatures {
        aidl = true
    }

    defaultConfig {
        applicationId = "com.eventsh.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("EVENTSH_KEYSTORE") ?: "../eventsh.jks")
            storePassword = System.getenv("EVENTSH_STORE_PASSWORD") ?: "eventsh123"
            keyAlias = System.getenv("EVENTSH_KEY_ALIAS") ?: "eventsh"
            keyPassword = System.getenv("EVENTSH_KEY_PASSWORD") ?: "eventsh123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let {
                if (it.storeFile?.exists() == true) signingConfig = it
            }
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
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.security:security-crypto:1.0.0")
}
