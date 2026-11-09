// AGP 9 has built-in Kotlin support, so the kotlin-android plugin must NOT be
// applied here — doing so is a hard error. Only the Compose compiler plugin is
// applied alongside it.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The version comes from the repository's VERSION file, so a release is one edit
// rather than two that can disagree. versionCode has to be a monotonically
// increasing integer, so it is derived rather than tracked separately: 1.2.3
// becomes 10203. That holds as long as minor and patch stay under 100.
val versionFile = rootProject.file("VERSION")
val rawVersion = versionFile.takeIf { it.exists() }?.readText()?.trim()
    ?: throw GradleException("VERSION not found at ${versionFile.absolutePath}")
val versionParts = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(rawVersion)
    ?: throw GradleException("VERSION must look like 1.2.3, got '$rawVersion'")
val (major, minor, patch) = versionParts.destructured

android {
    namespace = "dev.amrakshay.shutterlink"
    // The SDK package is "platforms;android-37.2" — the platform carries a minor
    // version now, hence compileSdkMinor.
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "dev.amrakshay.shutterlink"
        // Android 10. Chosen because BluetoothHidDevice (the selfie-stick shutter
        // trick, docs/PLAN.md §10) needs API 28, and Wi-Fi Direct discovery on
        // Android 10 already needs the location permission the newer path needs
        // anyway — so 29 is the floor where every feature here is reachable.
        minSdk = 29
        targetSdk = 36
        versionCode = major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
        versionName = rawVersion
    }

    // The release key comes from the environment, never from the repository.
    // Without it the release build is simply unsigned, which is what a clone
    // should get; CI checks for the secret and fails rather than publishing one.
    signingConfigs {
        val keystorePath = System.getenv("SHUTTERLINK_KEYSTORE")
        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SHUTTERLINK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SHUTTERLINK_KEY_ALIAS") ?: "shutterlink"
                keyPassword = System.getenv("SHUTTERLINK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
