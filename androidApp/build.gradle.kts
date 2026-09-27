import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/*
 * The Android app, and only the parts of it that belong to an *application*: the entry point (MainActivity),
 * the manifest and launcher resources, and the app's identity, version, signing and build types. The UI and
 * every Android platform actual live in :composeApp, which is an Android library -- AGP 9 no longer lets
 * com.android.application share a module with kotlin-multiplatform (see `agp` in libs.versions.toml).
 *
 * No Kotlin plugin here: AGP 9 compiles Kotlin itself ("built-in Kotlin"), using the KGP version the root
 * build puts on the classpath.
 */

/**
 * Release signing, loaded from `keystore.properties` at the repo root — gitignored, because it holds the
 * keystore password. See keystore.properties.example for the shape.
 *
 * When the file is absent the release build is simply UNSIGNED: `bundleRelease` still runs (useful for a
 * size/packaging check, and for anyone building the repo without the key), but the artifact cannot be
 * uploaded to Play.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Single-sourced from appVersion in the root gradle.properties (see the comment there).
val appVersion = providers.gradleProperty("appVersion").get()

android {
    // The app keeps the namespace and applicationId it has always had: the applicationId is its identity on
    // Play and on every installed device, and MainActivity's class name is what launcher shortcuts point at.
    namespace = "com.enuvro.saltykmp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.enuvro.saltykmp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Derived from appVersion (M.m.p → M*10_000_000 + m*100_000 + p, so minor < 100 and
        // patch < 100_000): upgrade ordering tracks the version with no hand-bumped counter.
        versionCode = appVersion.split(".").map { it.toInt() }
            .let { (major, minor, patch) -> major * 10_000_000 + minor * 100_000 + patch }
        versionName = appVersion
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        // Only declared when keystore.properties exists, so a checkout without the key still configures.
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            // Allow cleartext (HTTP) in debug only, so a local/dev Salty Server can be reached over
            // http:// for testing. Substituted into android:usesCleartextTraffic in the manifest.
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        getByName("release") {
            // Null when keystore.properties is absent → an unsigned artifact, which Play will reject.
            signingConfig = signingConfigs.findByName("release")
            // R8 is off: nothing here has been shrunk-tested, and SQLDelight/Ktor/kotlinx-serialization
            // all need keep rules that don't exist yet. Turning it on is its own piece of work.
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartextTraffic"] = "false" // release stays HTTPS-only
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    // MainActivity's setContent block is itself composable, and :composeApp keeps Compose as an
    // implementation detail, so the runtime the compiler plugin needs has to be declared here too.
    implementation(libs.compose.runtime)
    // Layout Inspector and friends in debug builds only. :composeApp can't carry this: the KMP library
    // plugin has no build types, so anything it adds would ship in release too.
    debugImplementation(libs.compose.uiTooling)
}
