plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

// CI passes the tag's version (v1.2.0 -> 1.2.0); locally it falls back to the default below.
val appVersion = (System.getenv("HMAPS_VERSION") ?: "1.1.3").trim()
val appCode = appVersion.split("-")[0].split(".").let {
    (it.getOrNull(0)?.toIntOrNull() ?: 1) * 10000 + (it.getOrNull(1)?.toIntOrNull() ?: 0) * 100 + (it.getOrNull(2)?.toIntOrNull() ?: 0)
}

// The Python server (hmaps/) and the web UI (web/) of the repository are packaged into the app.
val pySrc = layout.buildDirectory.dir("py").get().asFile
val webSrc = layout.buildDirectory.dir("webassets").get().asFile
// copied while Gradle configures the project, so every later task (python + assets merge) sees the files
delete(pySrc, webSrc)
copy {
    from("../../hmaps") { into("hmaps") }
    exclude("**/__pycache__/**", "**/*.pyc")
    into(pySrc)
}
copy {
    from("../../web") { into("web") }
    exclude("**/*.map")
    into(webSrc)
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
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    sourceSets["main"].assets.srcDir(webSrc)

    // Release key from the environment (CI secrets); without it the APK is signed with the local debug key.
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
    androidResources { noCompress += listOf("db", "mbtiles") }
    lint { abortOnError = false }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("starlette>=0.41,<2")
            install("uvicorn>=0.30")
            install("numpy==1.26.2")
            install("pillow")
            install("tzdata")
            install("zstandard")
        }
    }
    sourceSets["main"].srcDir(pySrc)
}
