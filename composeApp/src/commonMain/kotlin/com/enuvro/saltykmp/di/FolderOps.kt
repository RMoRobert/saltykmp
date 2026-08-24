package com.enuvro.saltykmp.di

import io.github.vinceglb.filekit.PlatformFile

/*
 * Directory primitives the linked-folder sync needs that FileKit 0.13 either lacks or gets wrong for
 * Android SAF tree URIs:
 *  - `PlatformFile.resolve(name)` on a tree URI *creates an empty file* when the child is missing (it maps to
 *    `DocumentFile.createFile`), so "resolve then exists()" can never report a missing file and leaves junk
 *    zero-byte files in the user's cloud folder;
 *  - `createDirectories()` throws on any content:// URI (it goes through kotlinx-io paths).
 * Desktop/iOS are plain paths where the obvious implementations apply.
 */

/** The existing child (file or directory) named [name], or null. Never creates anything. */
expect fun PlatformFile.findChild(name: String): PlatformFile?

/** The subdirectory [name] of this directory, created if missing. Throws if [name] exists but is a file. */
expect fun PlatformFile.ensureSubdirectory(name: String): PlatformFile

/**
 * A handle for the child file [name] that a subsequent `write` will create or overwrite. On Android/SAF the
 * document is created (empty) immediately, so only call this when you are about to write it.
 */
expect fun PlatformFile.childFile(name: String): PlatformFile
