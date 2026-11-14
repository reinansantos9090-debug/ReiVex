plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.reivex.browser"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    defaultConfig {
        applicationId = "com.reivex.browser"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("reivexBuildNumber").orNull?.toIntOrNull()?.takeIf { it >= 2 } ?: 2
        versionName = providers.gradleProperty("reivexBuildVersion").orNull?.takeIf { it.isNotBlank() } ?: "0.2.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("ciRelease") {
            val keystorePath = System.getenv("REIVEX_ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("REIVEX_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("REIVEX_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("REIVEX_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Release APKs must never silently inherit a changing debug key.
            // Only PR preview artifacts opt in to debug signing explicitly.
            signingConfig = when {
                !System.getenv("REIVEX_ANDROID_KEYSTORE_PATH").isNullOrBlank() ->
                    signingConfigs.getByName("ciRelease")
                System.getenv("REIVEX_ALLOW_DEBUG_RELEASE_FOR_PR") == "true" &&
                    System.getenv("GITHUB_EVENT_NAME") == "pull_request" ->
                    signingConfigs.getByName("debug")
                else -> null // Local release without a key remains unsigned, not debug-signed.
            }
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":browser-filter"))
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
