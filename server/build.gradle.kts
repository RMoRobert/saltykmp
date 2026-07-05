import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinxSerialization)
    application
}

group = "com.enuvro.saltykmp"
version = "2.9.100"
application {
    mainClass.set("com.enuvro.saltykmp.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

// Predictable runtime bytecode — matches the temurin:21-jre Docker base. Kotlin + Java targets aligned.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Stable fat-jar name for the Dockerfile: build/libs/salty-server.jar
ktor {
    fatJar {
        archiveFileName.set("salty-server.jar")
    }
}

// Bake the Gradle project version + build time into version.properties so the /about page (and any
// runtime reporting) reflects the real build. Keeps the version single-sourced from `version` above.
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    val appVersion = project.version.toString()
    val buildTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())
    inputs.property("appVersion", appVersion)
    inputs.property("buildTime", buildTime)
    filesMatching("version.properties") {
        expand(mapOf("appVersion" to appVersion, "buildTime" to buildTime))
    }
}

dependencies {
    implementation(projects.shared)
    implementation(libs.logback)

    // Ktor server
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverMustache)
    implementation(libs.ktor.serverSessions)
    implementation(libs.ktor.serverForwardedHeader)

    // Persistence
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.javatime)
    implementation(libs.exposed.json)
    implementation(libs.hikari)
    runtimeOnly(libs.postgres)
    // H2 on the runtime classpath enables running the server against H2 for local testing
    // (set SALTY_DB_DRIVER=org.h2.Driver). Production uses Postgres; H2 is only used if selected.
    runtimeOnly(libs.h2)

    // Auth
    implementation(libs.bcrypt)

    // Test
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.serverContentNegotiationTest)
    testImplementation(libs.kotlin.testJunit)
}
