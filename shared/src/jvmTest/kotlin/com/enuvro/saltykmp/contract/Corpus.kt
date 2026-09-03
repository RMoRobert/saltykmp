package com.enuvro.saltykmp.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.File

/**
 * One case from `salty-contract/corpus`: an input, an expected output, and the id of the rule in
 * `SPEC.md` it exists to pin. The Swift and .NET cores run the same cases through their own runners.
 *
 * [input] and [expect] stay as raw [JsonElement] because their shape depends on [op] — a string here,
 * an object there, a list somewhere else. Only the dispatcher in [ContractCorpusTest] knows which.
 */
@Serializable
data class CorpusCase(
    val id: String,
    val rule: String = "",
    val description: String = "",
    val op: String,
    val input: JsonElement = JsonNull,
    val expect: JsonElement = JsonNull,
    /**
     * Platforms known to fail this case, keyed `swift` / `kmp` / `dotnet`, with the reason. A platform
     * named here waives the case instead of failing — see `SPEC.md` §7 and `runners/README.md`.
     */
    @SerialName("known_divergence") val knownDivergence: Map<String, String>? = null,
) {
    /** Filled in by the loader; the JSON carries it once per file rather than once per case. */
    var suite: String = ""

    /** What a failure message leads with, so a red test names its own rule and rationale. */
    val because: String get() = "$suite/$id ($rule) $description"
}

@Serializable
private data class CorpusSuiteFile(val suite: String, val cases: List<CorpusCase>)

object CorpusLoader {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every case in every suite, in file then declaration order.
     *
     * An empty or missing corpus is a hard failure rather than a vacuously green run: a suite that
     * quietly asserts nothing is worse than one that is red.
     */
    fun load(): List<CorpusCase> {
        val directory = locate()
        val files = directory.listFiles { f: File -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()
        require(files.isNotEmpty()) { "No corpus files under '$directory'." }

        val cases = files.flatMap { file ->
            json.decodeFromString<CorpusSuiteFile>(file.readText())
                .let { suite -> suite.cases.onEach { it.suite = suite.suite } }
        }

        val duplicates = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate case ids: ${duplicates.joinToString()}" }

        return cases
    }

    /**
     * Finds `salty-contract/corpus`.
     *
     * The contract is its own repo, checked out beside this one, so there is no path to it from inside
     * this one. In order: the `SALTY_CORPUS_DIR` environment variable, then a walk up from the project
     * directory looking for `salty-contract/corpus`, checking each ancestor and each ancestor's
     * immediate children — the second of which is what finds the sibling checkout, and is how this
     * resolves in an ordinary working copy.
     *
     * The environment variable is the escape hatch for a layout this walk does not anticipate (CI that
     * checks the contract out somewhere else, or a second copy under test). Nothing sets it by default;
     * it replaced a `salty.corpusDir=` line in `local.properties`, which existed only while the
     * contract was a subdirectory of Salty.NET and no walk could reach it.
     */
    private fun locate(): File {
        System.getenv("SALTY_CORPUS_DIR")?.takeIf { it.isNotBlank() }?.let { configured ->
            val dir = File(configured)
            require(dir.isDirectory) { "SALTY_CORPUS_DIR is set to '$configured', which is not a directory." }
            return dir
        }

        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, "salty-contract/corpus").takeIf { it.isDirectory }?.let { return it }
            dir.listFiles { f: File -> f.isDirectory }
                ?.firstOrNull { File(it, "salty-contract/corpus").isDirectory }
                ?.let { return File(it, "salty-contract/corpus") }
            dir = dir.parentFile
        }

        error(
            "Could not find salty-contract/corpus.\n" +
                "It is its own repo; check it out beside this one:\n" +
                "    ${File(System.getProperty("user.dir")).absoluteFile.parent}/salty-contract\n" +
                "or set SALTY_CORPUS_DIR to wherever it is."
        )
    }
}
