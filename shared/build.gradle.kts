import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    iosArm64()
    iosSimulatorArm64()
    
    jvm()
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.runtime)
            implementation(libs.kotlinx.datetime)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.android)
            implementation(libs.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.native.driver)
        }
        jvmMain.dependencies {
            implementation(libs.jvm.driver)
        }
    }
}

// The conformance corpus lives outside this repo (see salty-contract/README.md), so there is no reliable
// relative path to it. Resolved from the SALTY_CORPUS_DIR environment variable, or from a
// `salty.corpusDir=` line in local.properties -- which is already this repo's home for machine-specific
// paths (sdk.dir) and is already gitignored, so no absolute path is ever checked in. Everything goes
// through `providers` to stay configuration-cache safe.
//
// When salty-contract becomes its own repo checked out beside this one, ContractCorpusTest's walk-up
// finds it unaided and this block can go.
val saltyCorpusDir: String? = providers.environmentVariable("SALTY_CORPUS_DIR")
    .orElse(
        providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText
            .map { text ->
                text.lineSequence()
                    .map(String::trim)
                    .firstOrNull { it.startsWith("salty.corpusDir=") }
                    ?.substringAfter('=')?.trim()
                    .orEmpty()
            }
    )
    .orNull?.takeIf { it.isNotBlank() }

tasks.withType<Test>().configureEach {
    saltyCorpusDir?.let { environment("SALTY_CORPUS_DIR", it) }
    testLogging {
        showStandardStreams = true // the corpus runner prints its waiver list and per-suite tallies
        events("passed", "failed", "skipped")
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.enuvro.saltykmp.db")
        }
    }
}

android {
    namespace = "com.enuvro.saltykmp.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}