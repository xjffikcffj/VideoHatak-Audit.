import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystorePath = (project.findProperty("VIDEOHATAK_KEYSTORE_PATH") as? String)
    ?: System.getenv("VIDEOHATAK_KEYSTORE_PATH")
val releaseKeystorePassword = (project.findProperty("VIDEOHATAK_KEYSTORE_PASSWORD") as? String)
    ?: System.getenv("VIDEOHATAK_KEYSTORE_PASSWORD")
val releaseKeyAlias = (project.findProperty("VIDEOHATAK_KEY_ALIAS") as? String)
    ?: System.getenv("VIDEOHATAK_KEY_ALIAS")
val releaseKeyPassword = (project.findProperty("VIDEOHATAK_KEY_PASSWORD") as? String)
    ?: System.getenv("VIDEOHATAK_KEY_PASSWORD")
val releaseKeystoreFile = releaseKeystorePath?.takeIf { it.isNotBlank() }?.let { file(it) }
val isReleaseSigningValid = releaseKeystoreFile != null &&
    releaseKeystoreFile.isFile &&
    releaseKeystoreFile.length() > 0L &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.videohatak.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.videohatak.player"
        minSdk = 24
        targetSdk = 36
        versionCode = 10000
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            if (isReleaseSigningValid) {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.timeout.set(Duration.ofMinutes(15))
            it.testLogging {
                events(
                    TestLogEvent.STARTED,
                    TestLogEvent.PASSED,
                    TestLogEvent.FAILED,
                    TestLogEvent.SKIPPED,
                    TestLogEvent.STANDARD_OUT,
                    TestLogEvent.STANDARD_ERROR
                )
                showExceptions = true
                showCauses = true
                showStackTraces = true
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.biometric)

    // Offline On-Device Speech Recognition (Vosk) & Translation (Google ML Kit)
    implementation(libs.google.mlkit.translate)
    implementation(libs.google.mlkit.language.id)
    implementation("com.alphacephei:vosk-android:0.3.75") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna:5.16.0@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

val releaseSigningValidationError = buildString {
    if (releaseKeystorePath.isNullOrBlank()) {
        append("Missing VIDEOHATAK_KEYSTORE_PATH. ")
    } else if (releaseKeystoreFile == null || !releaseKeystoreFile.isFile || releaseKeystoreFile.length() <= 0L) {
        append("Invalid or missing release keystore file at '$releaseKeystorePath'. ")
    }
    if (releaseKeystorePassword.isNullOrBlank()) append("Missing VIDEOHATAK_KEYSTORE_PASSWORD. ")
    if (releaseKeyAlias.isNullOrBlank()) append("Missing VIDEOHATAK_KEY_ALIAS. ")
    if (releaseKeyPassword.isNullOrBlank()) append("Missing VIDEOHATAK_KEY_PASSWORD. ")
}.trim()

val validateReleaseSigningConfig = tasks.register("validateReleaseSigningConfig") {
    val valid = isReleaseSigningValid
    val reason = releaseSigningValidationError
    doLast {
        if (!valid) {
            throw GradleException(
                "Release build aborted: $reason Unsigned Release APKs are disallowed."
            )
        }
    }
}

tasks.matching {
    it.name == "assembleRelease" ||
        it.name == "packageRelease" ||
        it.name == "bundleRelease" ||
        it.name == "validateSigningRelease"
}.configureEach {
    dependsOn(validateReleaseSigningConfig)
}
