plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.micro.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.micro.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // only the phone chips we need (keeps the app smaller)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Google MediaPipe hand tracking (for "air control" with hand gestures)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// Downloads the hand model file once while the app is being built (needs internet, GitHub Actions has it).
val handModel = layout.projectDirectory.file("src/main/assets/hand_landmarker.task").asFile
val downloadHandModel by tasks.registering {
    doLast {
        if (!handModel.exists() || handModel.length() < 1_000_000L) {
            try {
                handModel.parentFile.mkdirs()
                java.net.URL("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task")
                    .openStream().use { input -> handModel.outputStream().use { out -> input.copyTo(out) } }
                println("Hand model downloaded: " + handModel.length() + " bytes")
            } catch (e: Exception) {
                handModel.delete()
                println("Hand model download failed: " + e.message)
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadHandModel) }
