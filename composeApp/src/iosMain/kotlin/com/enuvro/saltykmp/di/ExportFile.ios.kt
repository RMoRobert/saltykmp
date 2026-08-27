package com.enuvro.saltykmp.di

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.shareFile

/**
 * iOS delivery is the share sheet (`UIActivityViewController`), which is what the Swift app's
 * `ShareLink` presents — the file is staged in the app's cache and offered to Messages, Mail, Files,
 * AirDrop, and to Salty itself on a receiving device (it registers the `.saltyRecipe` type).
 *
 * See the Android actual: the two bodies are the same because FileKit's `shareFile` sits in a source
 * set this project has no intermediate set for.
 */
actual suspend fun deliverExportedFile(stem: String, extension: String, bytes: ByteArray): ExportOutcome =
    shareStagedExport(FileKit, stem, extension, bytes) { shareFile(it) }
