plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.sarbloc.ardaas"
    // Compose BOM 2026.09 requires compiling against 37. compileSdk only
    // gates which APIs we may call; runtime behaviour opt-ins follow
    // targetSdk, kept at 36 until those changes are reviewed.
    compileSdk = 37

    defaultConfig {
        // Same identifier as the iOS app. The stores are separate namespaces,
        // so sharing it costs nothing and keeps the two apps recognisably one.
        applicationId = "io.sarbloc.ardaas"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
