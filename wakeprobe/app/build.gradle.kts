import java.io.File
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "tw.airgesture.wakeprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "tw.airgesture.wakeprobe"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1-experiment"
    }
    buildTypes { release { isMinifyEnabled = false } }
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

// Bundled offline gesture recognizer. Download happens only at build time.
val modelFile = layout.projectDirectory.file("src/main/assets/gesture_recognizer.task")
val downloadModel by tasks.registering {
    outputs.file(modelFile)
    doLast {
        val dest = modelFile.asFile
        if (!dest.exists() || dest.length() < 100_000L) {
            dest.parentFile.mkdirs()
            val url = "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
            val temp = File(dest.parentFile, "gesture_recognizer.task.part")
            try {
                URL(url).openConnection().apply { connectTimeout=30_000; readTimeout=120_000 }
                    .getInputStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                check(temp.length() > 100_000L)
                check(temp.renameTo(dest))
            } finally { temp.delete() }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadModel) }
