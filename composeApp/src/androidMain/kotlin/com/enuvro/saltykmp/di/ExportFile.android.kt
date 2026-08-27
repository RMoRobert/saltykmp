package com.enuvro.saltykmp.di

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.shareFile

/**
 * Android delivery is the system share sheet, matching the Swift app's iOS `ShareLink`: the file is
 * staged in the app's cache and handed to whichever target the user picks (Files, Drive, Mail, …).
 *
 * The staging directory is inside `Context.cacheDir`, which FileKit's own FileProvider already exposes,
 * so no manifest entry of ours is involved.
 *
 * This is duplicated in the iOS actual rather than shared: `shareFile` lives in FileKit's mobile source
 * set, which neither commonMain here nor any intermediate set of this project can see.
 */
actual suspend fun deliverExportedFile(stem: String, extension: String, bytes: ByteArray): ExportOutcome =
    shareStagedExport(FileKit, stem, extension, bytes) { shareFile(it) }
