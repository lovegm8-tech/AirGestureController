import java.io.File
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.airgesture.controller"
    compileSdk = 35
    defaultConfig {
        applicationId = "tw.airgesture.controller"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.2.8"
    }
    // Signing secrets are supplied only by the trusted, manually triggered CI run.
    // There is NO signing key or password in the repository.
    signingConfigs {
        val keystoreFile = System.getenv("AIRGESTURE_KEYSTORE_FILE")
        if (!keystoreFile.isNullOrBlank()) {
            create("fromCiSecrets") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("AIRGESTURE_STORE_PASSWORD")
                keyAlias = System.getenv("AIRGESTURE_KEY_ALIAS")
                keyPassword = System.getenv("AIRGESTURE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("fromCiSecrets") != null) {
                signingConfig = signingConfigs.getByName("fromCiSecrets")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    testImplementation("junit:junit:4.13.2")
}

// The model is bundled into the APK at build time; no runtime network access is needed.
// Source: official MediaPipe gesture recognizer model URL used by its Android sample.
val modelFile = layout.projectDirectory.file("src/main/assets/gesture_recognizer.task")
val downloadModel by tasks.registering {
    outputs.file(modelFile)
    doLast {
        val dest = modelFile.asFile
        if (!dest.exists() || dest.length() < 100_000L) {
            dest.parentFile.mkdirs()
            val uri = "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
            val temp = File(dest.parentFile, "gesture_recognizer.task.part")
            try {
                URL(uri).openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }.getInputStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                check(temp.length() > 100_000L) { "下載的手勢模型不完整" }
                check(temp.renameTo(dest)) { "無法儲存手勢模型" }
            } finally { temp.delete() }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadModel) }
