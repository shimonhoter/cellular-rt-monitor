plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cellular.rtmonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cellular.rtmonitor"
        minSdk = 24
        targetSdk = 34
        versionCode = ((System.getenv("VERSION_CODE")?.toIntOrNull() ?: 100) + 100)
        versionName = "4.1.9"
    }

    signingConfigs {
        create("persistentDebug") {
            val ksFile = file(System.getenv("KS_FILE_PATH") ?: "${System.getProperty("user.home")}/rtmonitor-release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASS") ?: "RtMonitorKey2026!"
                keyAlias = System.getenv("KEY_ALIAS") ?: "rtmonitor_key"
                keyPassword = System.getenv("KEY_PASS") ?: "RtMonitorKey2026!"
            }
        }
    }

    buildTypes {
        debug {
            val cfg = signingConfigs.findByName("persistentDebug")
            if (cfg?.storeFile?.exists() == true) {
                signingConfig = cfg
            }
        }
        release {
            isMinifyEnabled = false
            val cfg = signingConfigs.findByName("persistentDebug")
            if (cfg?.storeFile?.exists() == true) {
                signingConfig = cfg
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
