plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.reivex.browser"
    compileSdk = 36
    // Match the native ad blocker module so AGP can strip non-runtime DWARF
    // debug symbols from packaged .so files, instead of copying them intact.
    ndkVersion = "28.0.13004108"

    buildFeatures {
        buildConfig = true
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
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
        // Both the production APK and on-device preview run on arm64.
        // Do not bundle the emulator's x86_64 FFmpeg libraries in the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
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
        getByName("debug") {
            // Compact GitHub preview, while retaining an unminified local
            // Android Studio debug build for readable stack traces.
            if (providers.gradleProperty("reivexCompactDebug").orNull == "true") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
        }
        getByName("release") {
            // R8 removes unreachable Compose/AndroidX code and app resources.
            // Native FFmpeg and browser filtering are retained.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // Native FFmpeg/JNI for copy-only remux, using the minimal LGPL variant.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.9")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
