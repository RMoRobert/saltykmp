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
version = providers.gradleProperty("appVersion").get()
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

/*
 * The web UI at /app is a React + Fluent UI app under src/main/webapp, built by Vite and served
 * from the jar as ordinary static resources. Two tasks, both of which declare their inputs and
 * outputs so Gradle skips them entirely when nothing has changed -- a no-op build pays nothing.
 *
 * Plain `Exec` rather than the com.github.node-gradle.node plugin: that plugin's configuration
 * cache support is still an open issue, and this build uses the configuration cache. The cost is
 * that Node has to be on PATH, which for a single-maintainer project it is.
 */
val webappDir = layout.projectDirectory.dir("src/main/webapp")
val webappDist = layout.buildDirectory.dir("webapp")

val npmInstall by tasks.registering(Exec::class) {
    description = "Installs the web UI's npm dependencies."
    workingDir = webappDir.asFile
    commandLine("npm", "install", "--no-audit", "--no-fund")
    inputs.file(webappDir.file("package.json"))
    inputs.file(webappDir.file("package-lock.json"))
    // node_modules is the real output; naming it is what lets Gradle skip a warm install.
    outputs.dir(webappDir.dir("node_modules"))
}

val buildWebapp by tasks.registering(Exec::class) {
    description = "Builds the React web UI into build/webapp."
    dependsOn(npmInstall)
    workingDir = webappDir.asFile
    commandLine("npm", "run", "build")
    inputs.dir(webappDir.dir("src"))
    inputs.file(webappDir.file("index.html"))
    inputs.file(webappDir.file("vite.config.js"))
    inputs.file(webappDir.file("package-lock.json"))
    outputs.dir(webappDist)
}

// Bake the Gradle project version + build time into version.properties so the /about page (and any
// runtime reporting) reflects the real build. Keeps the version single-sourced from `appVersion`
// in the root gradle.properties.
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

    // /static/app/salty.js, which is what templates/app.mustache loads.
    dependsOn(buildWebapp)
    from(webappDist) {
        into("static/app")
        exclude("index.html") // the Mustache shell is the entry point; Vite's copy is for `npm run dev`
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
    // Drives a real browser for the web editor's UI tests. Ships its own driver and
    // downloads browsers on first run -- no Node toolchain in this build.
    testImplementation(libs.playwright)
}
