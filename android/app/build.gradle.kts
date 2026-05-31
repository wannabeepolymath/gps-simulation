plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gpssimulator.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gpssimulator.app"
        minSdk = 26
        targetSdk = 34
        // Bump both for every release.
        //   versionCode: +1 every time, monotonic, never reused. Android's update key.
        //   versionName: MAJOR.MINOR.PATCH — bug fix = patch, new feature = minor,
        //                breaking change (perms, data loss, removed feature) = major.
        // Git tag must be "v" + versionName (e.g. v0.5.1) for the in-app update check.
        versionCode = 3
        versionName = "0.6.0"

        val apiBaseUrl = (project.findProperty("api.base.url") as String?)
            ?: "http://10.0.2.2:4000"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")

        val googleWebClientId = (project.findProperty("google.web.client.id") as String?) ?: ""
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")

        val githubRepo = (project.findProperty("github.repo") as String?)
            ?: "wannabeepolymath/gps-simulation"
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    signingConfigs {
        create("release") {
            val ksPath = project.findProperty("STRAVA_SPOOF_KEYSTORE") as String?
            val ks = ksPath?.let { file(it) }
            if (ks != null && ks.exists()) {
                storeFile = ks
                storePassword = project.findProperty("STRAVA_SPOOF_KEYSTORE_PASSWORD") as String?
                keyAlias = project.findProperty("STRAVA_SPOOF_KEY_ALIAS") as String?
                keyPassword = project.findProperty("STRAVA_SPOOF_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksPath = project.findProperty("STRAVA_SPOOF_KEYSTORE") as String?
            if (ksPath != null && file(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Google Sign-In via Credential Manager (no Firebase).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // HTTP client for backend
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
