package com.enuvro.saltykmp.di

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.resolve

// Plain filesystem paths: resolve() is a pure path join here.
actual fun PlatformFile.findChild(name: String): PlatformFile? = resolve(name).takeIf { it.exists() }

actual fun PlatformFile.ensureSubdirectory(name: String): PlatformFile = resolve(name).also { it.createDirectories() }

actual fun PlatformFile.childFile(name: String): PlatformFile = resolve(name)
