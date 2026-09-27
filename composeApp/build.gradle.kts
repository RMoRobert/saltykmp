import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    // An Android *library* since AGP 9, which no longer lets com.android.application share a module with
    // kotlin-multiplatform; the app itself (identity, signing, build types, MainActivity) is :androidApp.
    android {
        // Must differ from the app's own "com.enuvro.saltykmp" -- AGP 9 requires unique namespaces.
        namespace = "com.enuvro.saltykmp.composeapp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        // Compose resources (composeResources/) reach Android as Android resources, and the KMP library
        // plugin leaves those off unless asked.
        androidResources {
            enable = true
        }
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // compatible with Compose 1.11 at runtime.
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation(libs.compose.ui)
            // System/gesture back (NavigationBackHandler). Compose's own BackHandler is deprecated in its favour.
            implementation(libs.navigationevent.compose)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            // Local-time formatting for the Settings "Last synced:" line.
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.filekit.dialogs.compose)
            implementation(projects.shared)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // On-device tests: the AndroidKeyStore has no JVM equivalent, so the crypto in
        // SecretStore.android.kt can only be exercised on an emulator or handset.
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.androidx.testExt.junit)
            implementation(libs.androidx.test.runner)
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

// Single-sourced from appVersion in the root gradle.properties (see the comment there).
val appVersion = providers.gradleProperty("appVersion").get()

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
                // Screen readers on Windows (NVDA, JAWS) reach a Java app through the Java Access Bridge.
                // Once a user turns it on (jabswitch /enable), AWT loads it at startup, and without this
                // module the packaged app died with "Failed to launch JVM" (found on Windows, 2026-09-22).
                "jdk.accessibility",
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
