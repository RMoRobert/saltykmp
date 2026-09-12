package com.enuvro.saltykmp

import com.enuvro.saltykmp.di.makeThumbnail
import com.enuvro.saltykmp.importer.RecipeFileImportSummary
import com.enuvro.saltykmp.importer.SaltyRecipeImporter
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Import from File…: adds the recipes in the `.saltyRecipe` files the picker handed back.
 *
 * Reading, filing and reporting live in `shared` ([SaltyRecipeImporter]) and are tested there; this is
 * the layer that knows about [PlatformFile], the app's image folder, and the syncers to tell.
 */
object RecipeFileImport {

    suspend fun run(module: AppModule, files: List<PlatformFile>): RecipeFileImportSummary {
        val summary = withContext(Dispatchers.Default) {
            val importer = SaltyRecipeImporter(
                module.database,
                module.localStore,
                saveImage = { filename, bytes -> module.imageFiles.save(filename, bytes) },
                makeThumbnail = { bytes -> makeThumbnail(bytes, THUMBNAIL_MAX_PX) },
            )
            importer.importFiles(
                files.map { file ->
                    // A provider that can't say how big a file is reports it as 0, which reads it anyway:
                    // the size cap is a guard against the absurd, not something every file must prove.
                    SaltyRecipeImporter.PickedFile(file.name, runCatching { file.size() }.getOrDefault(0L)) {
                        file.readBytes()
                    }
                },
            )
        }
        // Once for the whole batch; auto-sync and the linked-folder push each debounce anyway.
        if (summary.imported.isNotEmpty()) module.onLocalChange()
        return summary
    }
}
