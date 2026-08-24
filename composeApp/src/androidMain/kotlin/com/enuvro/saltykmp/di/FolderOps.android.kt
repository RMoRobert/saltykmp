package com.enuvro.saltykmp.di

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import java.io.File

// SAF-aware directory ops (see the commonMain expect declarations for why FileKit's own helpers won't do).
// A linked folder arrives as a tree URI; its children are document URIs under that tree, which
// DocumentFile.fromTreeUri (documentfile ≥ 1.1.0) resolves correctly for listing/creating grandchildren.

actual fun PlatformFile.findChild(name: String): PlatformFile? = when (val f = androidFile) {
    is AndroidFile.FileWrapper -> File(f.file, name).takeIf { it.exists() }?.let { PlatformFile(it) }
    is AndroidFile.UriWrapper -> treeDocument(f.uri)?.findFile(name)?.let { PlatformFile(it.uri) }
}

actual fun PlatformFile.ensureSubdirectory(name: String): PlatformFile = when (val f = androidFile) {
    is AndroidFile.FileWrapper -> PlatformFile(File(f.file, name).apply { mkdirs() })
    is AndroidFile.UriWrapper -> {
        val dir = treeDocument(f.uri) ?: error("Linked folder is not a directory: ${f.uri}")
        val existing = dir.findFile(name)
        val child = when {
            existing == null -> dir.createDirectory(name) ?: error("Could not create folder '$name' in the linked folder")
            existing.isDirectory -> existing
            else -> error("'$name' already exists in the linked folder but is a file, not a folder")
        }
        PlatformFile(child.uri)
    }
}

actual fun PlatformFile.childFile(name: String): PlatformFile = when (val f = androidFile) {
    is AndroidFile.FileWrapper -> PlatformFile(File(f.file, name))
    // FileKit's child constructor does the right thing for documents: find the existing child, else
    // DocumentFile.createFile(mime-from-extension, name).
    is AndroidFile.UriWrapper -> PlatformFile(this, name)
}

private fun treeDocument(uri: Uri): DocumentFile? = DocumentFile.fromTreeUri(androidAppContext, uri)
