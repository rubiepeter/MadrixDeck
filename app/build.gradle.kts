plugins {
    id("com.android.application")
}

android {
    namespace = "au.com.peterrubie.madrixdeck"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.com.peterrubie.madrixdeck"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "2.4"
    }

    // Fixed key so each GitHub build installs over the previous one and keeps your edits.
    signingConfigs {
        getByName("debug") {
            storeFile = file("madrixdeck-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
