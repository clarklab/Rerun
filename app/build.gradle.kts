plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "team.superfun.rerun"
    compileSdk = 35

    defaultConfig {
        applicationId = "team.superfun.rerun"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK can be sideloaded without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    androidResources {
        // Store video uncompressed: it's already compressed, and this lets
        // ExoPlayer seek straight into the APK instead of inflating a stream.
        noCompress += listOf("mp4", "m4v", "mkv", "webm")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-effect:$media3")

    testImplementation("junit:junit:4.13.2")
}
