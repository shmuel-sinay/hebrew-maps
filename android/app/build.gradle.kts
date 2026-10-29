plugins {
    id("com.android.application")
}

// CI passes the tag's version (v1.2.0 -> 1.2.0); locally it falls back to the default below.
val appVersion = (System.getenv("HMAPS_VERSION") ?: "1.1.4").trim()
val appCode = appVersion.split("-")[0].split(".").let {
    (it.getOrNull(0)?.toIntOrNull() ?: 1) * 10000 + (it.getOrNull(1)?.toIntOrNull() ?: 0) * 100 + (it.getOrNull(2)?.toIntOrNull() ?: 0)
}

android {
    namespace = "il.hebrewmaps.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "il.hebrewmaps.android"
        minSdk = 24
        targetSdk = 34
        versionCode = appCode
        versionName = appVersion
    }

    // Signing key comes from the environment (CI); without it the release APK is signed with the local debug key.
    signingConfigs {
        create("release") {
            val ks = System.getenv("HMAPS_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("HMAPS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HMAPS_KEY_ALIAS") ?: "hebrewmaps"
                keyPassword = System.getenv("HMAPS_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("HMAPS_KEYSTORE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
    }
}
