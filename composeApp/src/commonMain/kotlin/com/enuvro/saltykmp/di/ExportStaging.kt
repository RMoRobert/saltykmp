package com.enuvro.saltykmp.di

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.lastModified
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.write

/**
 * Writes an export into a staging directory in the app's cache and hands it to [share] — the body of
 * the Android and iOS [deliverExportedFile] actuals, which differ only in which FileKit share function
 * they can see (it lives in a source set neither this module's commonMain nor `shared` can reach).
 *
 * A share sheet target reads the file AFTER the sheet is dismissed, so the staging directory can't be
 * emptied on every export — that would pull the file out from under an in-flight share. Instead the few
 * most recent are kept and older ones pruned, which bounds the cache without ever touching the file the
 * user just acted on.
 */
internal suspend fun shareStagedExport(
    fileKit: FileKit,
    stem: String,
    extension: String,
    bytes: ByteArray,
    share: suspend FileKit.(PlatformFile) -> Unit,
): ExportOutcome = try {
    val dir = fileKit.cacheDir / EXPORT_STAGING_DIR
    if (!dir.exists()) dir.createDirectories()
    pruneStagedExports(dir)
    val file = dir / "$stem.$extension"
    file.write(bytes)
    fileKit.share(file)
    ExportOutcome.Shared
} catch (e: Throwable) {
    ExportOutcome.Failed(e.message)
}

/** Best-effort: a cache we can't tidy is not a reason to fail the export the user asked for. */
private suspend fun pruneStagedExports(dir: PlatformFile) {
    runCatching {
        dir.list()
            .sortedByDescending { it.lastModified().toEpochMilliseconds() }
            .drop(KEEP_RECENT_EXPORTS)
            .forEach { it.delete(mustExist = false) }
    }
}

private const val EXPORT_STAGING_DIR = "exports"
private const val KEEP_RECENT_EXPORTS = 5
