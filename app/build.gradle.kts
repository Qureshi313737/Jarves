plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarves.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarves.assistant"
        minSdk = 26 // Android 8.0+
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // One permanent key, so every new build installs as an update over the previous one.
    // To use your own private key, set the JARVES_KEYSTORE / JARVES_KEYSTORE_PASSWORD /
    // JARVES_KEY_ALIAS / JARVES_KEY_PASSWORD environment variables (e.g. from GitHub secrets).
    signingConfigs {
        create("jarves") {
            storeFile = file(System.getenv("JARVES_KEYSTORE") ?: "jarves-release.jks")
            storePassword = System.getenv("JARVES_KEYSTORE_PASSWORD") ?: "jarves-release-2026"
            keyAlias = System.getenv("JARVES_KEY_ALIAS") ?: "jarves"
            keyPassword = System.getenv("JARVES_KEY_PASSWORD") ?: "jarves-release-2026"
            // All signature schemes, so every device / installer accepts the APK.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        // Everything, including screen control (Accessibility) and notification reading.
        create("full") {
            dimension = "edition"
        }
        // No Accessibility / notification access: installs even where Play Protect blocks those.
        create("lite") {
            dimension = "edition"
            versionNameSuffix = "-lite"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("jarves")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarves")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Lint must never stop the APK from being produced.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/9/module-info.class",
                "module-info.class",
            )
        }
    }
}

dependencies {
    // Official Anthropic SDK (Claude API) — the assistant's brain.
    implementation("com.anthropic:anthropic-java:3.6.0")
}
