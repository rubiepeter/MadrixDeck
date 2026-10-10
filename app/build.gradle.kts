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
        versionCode = 2
        versionName = "2.0"
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
