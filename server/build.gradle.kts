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

/*
 * Web assets are packaged minified. Vite already minifies the React bundle, but the Mustache
 * shells and the classic view's stylesheet went into the jar exactly as written -- comments,
 * indentation and all -- so every visitor downloaded the maintenance notes meant for whoever edits
 * them next. This strips them at packaging time only; the sources under src/main/resources stay
 * readable. Turn it off with -PminifyWeb=false (or minifyWeb in gradle.properties) when a served
 * page has to be read as-is.
 *
 * Hand-rolled rather than a plugin, for the same reason npm is driven by a plain Exec above, and
 * deliberately conservative: whitespace is never collapsed *within* a line, so significant space
 * between inline elements survives, and <pre>/<textarea>/<script> bodies are held out entirely.
 * The one assumption is that no literal HTML comment appears inside those held-out bodies --
 * comments are stripped before the bodies are fenced off, because a `<script>` merely *mentioned*
 * inside a comment would otherwise fence off the comment itself.
 */
abstract class MinifyWebResources : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun minify() {
        val root = sourceRoot.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        var before = 0L
        var after = 0L
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val minified = when {
                path.startsWith("templates/") && file.extension == "mustache" -> minifyHtml(file.readText())
                path.startsWith("static/") && file.extension == "css" -> minifyCss(file.readText()) + "\n"
                else -> return@forEach
            }
            val target = out.resolve(path)
            target.parentFile.mkdirs()
            target.writeText(minified)
            before += file.length()
            after += target.length()
        }
        val saved = if (before == 0L) 0L else (before - after) * 100 / before
        logger.lifecycle("Minified web resources: $before -> $after bytes ($saved% smaller)")
    }

    private fun minifyHtml(source: String): String {
        val heldOut = mutableListOf<String>()
        var text = source.replace(HTML_COMMENT, "").replace(MUSTACHE_COMMENT, "")
        text = RAW_TEXT_ELEMENT.replace(text) { match ->
            heldOut += match.value
            "%%SALTY-HELD-${heldOut.size - 1}%%"
        }
        text = STYLE_BLOCK.replace(text) { match ->
            val (open, css, close) = match.destructured
            open + minifyCss(css) + close
        }
        text = text.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString("\n")
        return PLACEHOLDER.replace(text) { match -> heldOut[match.groupValues[1].toInt()] } + "\n"
    }

    // Comments and line structure go; spacing around `:` and the combinators stays, because
    // `.a :hover` (descendant) and `.a:hover` (same element) are different selectors.
    private fun minifyCss(source: String): String = source
        .replace(CSS_COMMENT, "")
        .replace(WHITESPACE, " ")
        .let { css -> CSS_PUNCTUATION.replace(css) { it.groupValues[1] } }
        .replace(";}", "}")
        .trim()

    private companion object {
        val HTML_COMMENT = Regex("<!--(?!\\[if)[\\s\\S]*?-->")
        val MUSTACHE_COMMENT = Regex("\\{\\{!.*?\\}\\}", RegexOption.DOT_MATCHES_ALL)
        val RAW_TEXT_ELEMENT =
            Regex("<(pre|textarea|script)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
        val STYLE_BLOCK = Regex("(<style\\b[^>]*>)([\\s\\S]*?)(</style\\s*>)", RegexOption.IGNORE_CASE)
        val PLACEHOLDER = Regex("%%SALTY-HELD-(\\d+)%%")
        val CSS_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        val WHITESPACE = Regex("\\s+")
        val CSS_PUNCTUATION = Regex("\\s*([{};,])\\s*")
    }
}

val minifyWeb = providers.gradleProperty("minifyWeb").map(String::toBoolean).getOrElse(true)

val minifyWebResources by tasks.registering(MinifyWebResources::class) {
    description = "Strips comments and indentation from the Mustache shells and the classic stylesheet."
    sourceRoot.set(layout.projectDirectory.dir("src/main/resources"))
    outputDir.set(layout.buildDirectory.dir("minified-resources"))
}

if (minifyWeb) {
    // The minified copies stand in for the originals at the same resource paths, so the originals
    // have to leave the source set -- an `exclude` on processResources itself would propagate to
    // every spec that task copies, the replacements included.
    sourceSets.named("main") { resources.exclude("templates/**", "static/**/*.css") }
    tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
        from(minifyWebResources)
    }
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
    implementation(libs.ktor.serverMustache)
    implementation(libs.ktor.serverSessions)
    implementation(libs.ktor.serverForwardedHeader)
    implementation(libs.ktor.serverCompression)
    implementation(libs.ktor.serverConditionalHeaders)
    implementation(libs.ktor.serverCachingHeaders)

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

