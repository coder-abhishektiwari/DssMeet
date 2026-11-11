import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Custom keys in local.properties are NOT auto-exposed to findProperty (only
// sdk.dir is). Load them explicitly so endpoint config lives in ONE file and
// survives rebuilds without touching this script.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun appProp(name: String, fallback: String): String =
    (project.findProperty(name) as String?)
        ?: localProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: fallback

// Release signing. The keystore is NEVER in the repo: CI passes it through
// environment variables (DSM_KEYSTORE_PATH + passwords) and a local build
// can put the same four keys in local.properties. Without them the release
// build stays UNSIGNED (fine for a fresh install, useless for an in-app
// update — a different key can never overwrite an installed APK).
val signingKeystorePath: String? =
    System.getenv("DSM_KEYSTORE_PATH")
        ?: localProperties.getProperty("KEYSTORE_PATH")?.trim()?.takeIf { it.isNotEmpty() }
val signingStorePassword: String? =
    System.getenv("DSM_KEYSTORE_PASSWORD")
        ?: localProperties.getProperty("KEYSTORE_PASSWORD")?.trim()?.takeIf { it.isNotEmpty() }
val signingKeyAlias: String? =
    System.getenv("DSM_KEY_ALIAS")
        ?: localProperties.getProperty("KEY_ALIAS")?.trim()?.takeIf { it.isNotEmpty() }
val signingKeyPassword: String? =
    System.getenv("DSM_KEY_PASSWORD")
        ?: localProperties.getProperty("KEY_PASSWORD")?.trim()?.takeIf { it.isNotEmpty() }
val hasReleaseSigning = signingKeystorePath != null &&
    signingStorePassword != null &&
    signingKeyAlias != null &&
    signingKeyPassword != null

android {
    namespace = "com.dssmeet.kiosk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dssmeet.kiosk"
        minSdk = 26
        targetSdk = 35
        // versionCode drives the in-app updater (it compares versionCode, so
        // it MUST increase with every release). CI passes it via -P.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Backend endpoint & call-service config.
        // Priority: gradle -P flag > local.properties > default below.
        // NOTE: SIGNALING_URL is only a FALLBACK - at runtime the backend
        // delivers the fresh public signaling URL inside every create-call
        // response, so tunnel changes never require a rebuild.
        // Defaults are LOCAL-ONLY. No deployment host is baked into the
        // source: set API_BASE_URL / SIGNALING_URL in local.properties (or
        // via -P flags) for a real endpoint. TURN/ICE credentials are NOT
        // baked in — the signaling server ships iceServers in every
        // join-room ACK. NOTE: there is NO build-time KIOSK_ID — kiosk
        // identity is server-issued via /kiosks/verify after registration.
        val apiBaseUrl = appProp("API_BASE_URL", "http://127.0.0.1:59354")
        val signalingUrl = appProp("SIGNALING_URL", "http://127.0.0.1:3002")
        val autoLogoutMs = appProp("AUTO_LOGOUT_TIMEOUT_MS", "120000")

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "SIGNALING_URL", "\"$signalingUrl\"")
        buildConfigField("long", "AUTO_LOGOUT_TIMEOUT_MS", "${autoLogoutMs}L")

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Without the keystore the APK stays unsigned (fresh installs
            // only). The build prints a warning so nobody ships one by
            // accident.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

if (!hasReleaseSigning) {
    logger.warn(
        "[signing] No release keystore configured (DSM_KEYSTORE_PATH / KEYSTORE_PATH) — " +
            "the release APK will be UNSIGNED. It can only be side-loaded fresh, " +
            "never update an already installed kiosk."
    )
}

dependencies {
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)

    // Jetpack Compose (Material 3)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.window.size)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Network Layer (Retrofit + OkHttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.mockwebserver)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play)

    // Socket.IO Client (WebRTC signaling)
    implementation(libs.socketio.client)

    // WebRTC Native Client
    implementation(libs.google.webrtc)

    // Coil (image loading)
    implementation(libs.coil.compose)

    // Lottie (RFID tap animation)
    implementation(libs.lottie.compose)

    // DataStore (secure token persistence)
    implementation(libs.androidx.datastore.preferences)

    // WorkManager: recording uploads must outlive the process
    implementation(libs.androidx.work.runtime.ktx)
}
