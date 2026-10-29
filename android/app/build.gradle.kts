plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

// CI passes the tag's version (v1.2.0 -> 1.2.0); locally it falls back to the default below.
val appVersion = (System.getenv("HMAPS_VERSION") ?: "1.1.4").trim()
val appCode = appVersion.split("-")[0].split(".").let {
    (it.getOrNull(0)?.toIntOrNull() ?: 1) * 10000 + (it.getOrNull(1)?.toIntOrNull() ?: 0) * 100 + (it.getOrNull(2)?.toIntOrNull() ?: 0)
}

// The Python server (hmaps/) and the web UI (web/) of the repository are packaged into the app.
val pySrc = layout.buildDirectory.dir("py")
val webSrc = layout.buildDirectory.dir("webassets")
val copyServer by tasks.registering(Sync::class) {
    from("../../hmaps") { into("hmaps") }
    from("src/main/python")
    exclude("**/__pycache__/**", "**/*.pyc")
    into(pySrc)
}
val copyWeb by tasks.registering(Sync::class) {
    from("../../web") { into("web") }
    exclude("**/*.map")
    into(webSrc)
}
tasks.named("preBuild") { dependsOn(copyServer, copyWeb) }

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

    // Fixed project key so every release APK installs as an update over the previous one.
    signingConfigs {
        create("release") {
            storeFile = file("hebrewmaps-release.jks")
            storePassword = "hebrewmaps"
            keyAlias = "hebrewmaps"
            keyPassword = "hebrewmaps"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
