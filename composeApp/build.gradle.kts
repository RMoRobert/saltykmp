import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

// FileKit transitively bumps the Skiko *classes* (0.9.37.4) above the Skiko *native runtime* that
// Compose pins, causing an UnsatisfiedLinkError on desktop. Pin every Skiko module to one version so
// the loaded native library matches the classes.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.skiko") {
            useVersion("0.9.37.4")
        }
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    jvm()
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.android)
            // SAF DocumentFile ops for the linked-folder sync (FolderOps.android.kt); FileKit pulls it in
            // transitively, pinned here because we call it directly.
            implementation(libs.androidx.documentfile)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            // 1.7.3 is the last published Compose material-icons-extended; pure-Kotlin ImageVectors,
            // compatible with Compose 1.10 at runtime.
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation(libs.compose.ui)
            // Multiplatform BackHandler (system/gesture back) lives in its own artifact.
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.10.0")
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.filekit.dialogs.compose)
            implementation(projects.shared)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktor.client.cio)
            // Windows Credential Manager bindings for the desktop SecretStore; unused (but harmless)
            // on macOS/Linux, which fall back to the obfuscated store.
            implementation(libs.jna)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

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

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.enuvro.saltykmp.MainKt"

        // ProGuard, which the `packageRelease*` tasks run by default, is off for the same reason R8 is
        // off for the Android release build: SQLDelight/Ktor/kotlinx-serialization all need keep rules
        // that don't exist yet, and a stripped desktop build fails at runtime, not at build time.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // jlink strips the bundled runtime to the modules it can detect, and it cannot see
            // SQLDelight loading the SQLite JDBC driver reflectively -- without java.sql the packaged
            // app dies on first DB access, while `run` (which uses the full JDK) is perfectly happy.
            // Regenerate this list with `./gradlew :composeApp:suggestRuntimeModules`.
            modules(
                "java.instrument",
                "java.management",
                "java.prefs",        // KeyValueStore on desktop (Preferences.userRoot)
                "java.sql",          // SQLDelight / sqlite-jdbc
                "jdk.security.auth",
                "jdk.unsupported",
            )
            // The installed app is "Salty", not "com.enuvro.saltykmp" — that reverse-DNS string is an
            // identifier, and it was showing up as the application name in Finder and the Dock.
            packageName = "Salty"
            packageVersion = appVersion
            macOS {
                bundleID = "com.enuvro.saltykmp"
                dockName = "Salty"
            }
            // Debian package names must be lowercase.
            linux { packageName = "salty" }
            windows {
                menuGroup = "Salty"
                shortcut = true       // Start-menu and desktop shortcuts
                dirChooser = true     // let the installer offer a location
                // Per-user install into the user's AppData: no UAC prompt, no admin rights needed --
                // this app has no reason to write outside the user's own profile.
                perUserInstall = true
                // Identifies the *product* to Windows Installer: 3.2.101 replaces 3.2.100 only because
                // this GUID is the same in both. Never change it, or upgrades install side by side.
                upgradeUuid = "D154A5E2-AB58-4C69-A6EE-E015DB6330DE"
                iconFile.set(project.file("icons/salty.ico"))
            }
        }
    }
}
