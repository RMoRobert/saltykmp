package com.enuvro.saltykmp.di

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.write

/**
 * Desktop delivery is a Save dialog — the same shape as the Swift app's macOS export panel. The user
 * picks the destination, so nothing is written until they do, and a dismissed dialog is a plain cancel
 * rather than an error.
 */
actual suspend fun deliverExportedFile(stem: String, extension: String, bytes: ByteArray): ExportOutcome =
    try {
        // `defaultExtension`, not the deprecated `extension`: FileKit 0.15 split the two, because this
        // is the extension the dialog pre-fills, never a filter on what the user may pick.
        val file = FileKit.openFileSaver(suggestedName = stem, defaultExtension = extension)
            ?: return ExportOutcome.Cancelled
        file.write(bytes)
        ExportOutcome.Saved(runCatching { file.absolutePath() }.getOrNull() ?: file.name)
    } catch (e: Throwable) {
        ExportOutcome.Failed(e.message)
    }
