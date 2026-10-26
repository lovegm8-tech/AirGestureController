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
        versionCode = 67
        versionName = "1.0.54"
        // S26 Ultra / S25 are arm64-v8a. Remove unused x86/x86_64/32-bit
        // copies of MediaPipe and Vosk without stripping needed functionality.
        ndk { abiFilters += setOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DSENTENCEPIECE_SOURCE_DIR=${project.rootDir}/native-deps/sentencepiece", "-DANDROID_STL=c++_shared"); targets += setOf("nllb_sentencepiece") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Both sherpa-onnx and standalone ORT ship libonnxruntime.so.
        // Packaging workaround only; runtime compatibility requires device verification.
        jniLibs { pickFirsts += "**/libonnxruntime.so" }
    }
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
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    // Android CPU ONNX Runtime for dedicated offline NLLB graph evaluation.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("com.alphacephei:vosk-android:0.3.75")
    // Official k2-fsa sherpa-onnx v1.13.8 AAR; pinned and SHA-256 checked by CI.
    // Stored under app/libs at CI build time; not committed to public GitHub.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Official Google LiteRT-LM Android runtime. Model weights are NOT in APK.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
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
