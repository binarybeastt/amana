import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.offlinezec.wallet"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.offlinezec.wallet"
        minSdk = 27 // same as the ZODL SDK 5.2.1
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Our Rust library is built for arm64 only (all demo phones and the Apple-silicon
        // emulators are arm64). The SDK ships more ABIs; keep only the one we can run.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // required by the SDK
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // The SDK's native library is large; keep it uncompressed and page-aligned.
        jniLibs { useLegacyPackaging = false }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// offline-core (Rust) -> app/src/main/jniLibs/arm64-v8a/liboffline_core.so, before every build.
// Needs: Rust, `cargo install cargo-ndk`, the Android NDK (ANDROID_NDK_HOME or the SDK's ndk/).
val buildOfflineCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds offline-core for arm64 Android with JNI entry points"
    val crate = rootProject.file("../offline-core")
    workingDir = crate
    inputs.dir(crate.resolve("src"))
    inputs.files(crate.resolve("Cargo.toml"), crate.resolve("Cargo.lock"))
    outputs.dir(file("src/main/jniLibs"))
    val sdkDir = android.sdkDirectory
    doFirst {
        if (System.getenv("ANDROID_NDK_HOME") == null) {
            val ndk = sdkDir.resolve("ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
                ?: throw GradleException("No NDK under ${sdkDir}/ndk; install one or set ANDROID_NDK_HOME")
            environment("ANDROID_NDK_HOME", ndk.absolutePath)
        }
    }
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "--platform", "27",
        "-o", file("src/main/jniLibs").absolutePath,
        "build", "--release", "--lib", "--no-default-features", "--features", "jni",
    )
}
tasks.named("preBuild") { dependsOn(buildOfflineCore) }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // ZODL Zcash Android Wallet SDK (AGPL-3.0-only), the NU7-aware continuation of
    // zcash-android-sdk. offline-core pins the same librustzcash crates (see its Cargo.toml).
    implementation("com.zodl.android:zcash-android-sdk:5.2.1-SNAPSHOT")
    implementation("cash.z.ecc.android:kotlin-bip39:1.0.9")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // AndroidX versions as in the SDK 3.0.1 build (newer Compose needs AGP 9.1).
    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.foundation:foundation:1.10.5")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Animated QR: ZXing renders and decodes QR codes; Hummingbird (BC-UR) fountain-codes the
    // transaction across frames, so missed frames are made up by later ones.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.sparrowwallet:hummingbird:1.7.6")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.guava:guava:33.4.8-android") // ListenableFuture in the CameraX API

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
}
