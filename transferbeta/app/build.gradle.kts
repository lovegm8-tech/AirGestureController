import java.io.File
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    buildFeatures { buildConfig = true }
    namespace = "tw.airgesture.transferbeta"
    compileSdk = 35
    defaultConfig {
        applicationId = "tw.airgesture.transferbeta"
        minSdk = 29
        targetSdk = 35
        versionCode = 25
        versionName = "1.0.12"
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
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("androidx.media3:media3-transformer:1.8.1")
    implementation("androidx.media3:media3-effect:1.8.1")
    implementation("androidx.media3:media3-common:1.8.1")
    // One upgrade-cycle only: cancels WorkManager jobs scheduled by the retired updater.
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    testImplementation("junit:junit:4.13.2")
}
val modelFile = layout.projectDirectory.file("src/main/assets/gesture_recognizer.task")
val downloadModel by tasks.registering {
    outputs.file(modelFile)
    doLast {
        val dest = modelFile.asFile
        if (!dest.exists() || dest.length() < 100_000) {
            dest.parentFile.mkdirs()
            val tmp = File(dest.parentFile, "gesture_recognizer.part")
            try {
                URL("https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task")
                    .openConnection().apply { connectTimeout = 30000; readTimeout = 120000 }
                    .getInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                check(tmp.length() > 100_000) { "Model download incomplete" }
                check(tmp.renameTo(dest)) { "Model move failed" }
            } finally { tmp.delete() }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadModel) }
