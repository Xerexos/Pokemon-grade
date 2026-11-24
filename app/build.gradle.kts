plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI sets these; local builds fall back to version 1 and the debug key.
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
val uploadKeystore = System.getenv("UPLOAD_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.pokegrade.app"
    // Google Play requires new apps to target Android 16 (API 36).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pokegrade.app"
        minSdk = 26
        targetSdk = 36
        // Every CI build gets a higher version code so Play accepts it as an update.
        versionCode = ciVersionCode ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = uploadKeystore
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Play builds are signed with your upload key (Google re-signs them with the
            // app signing key). Without it, release builds use the debug key so the
            // APK still installs directly for testing.
            signingConfig = if (uploadKeystore != null) signingConfigs.getByName("upload")
            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/versions/9/previous-compilation-data.bin", "META-INF/*.version",
                "META-INF/FastDoubleParser-*", "META-INF/AL2.0", "META-INF/LGPL2.1",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // On-device text recognition (reads card name, HP and number).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Japanese, Chinese and Korean text, built into the app so they work offline
    // right after install (the Play-services versions download later and can fail silently).
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Shared dataset (only active when app/google-services.json is present).
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    // Firebase pulls in an empty "listenablefuture" placeholder; CameraX needs the real class.
    implementation("com.google.guava:guava:33.3.1-android")
}

// Firebase config comes from app/google-services.json (kept out of git; CI writes it
// from the GOOGLE_SERVICES_JSON secret). Without it the app builds with uploads disabled.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
