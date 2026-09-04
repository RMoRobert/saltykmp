package com.enuvro.saltykmp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.importer.ParsedRecipe
import com.enuvro.saltykmp.importer.WebImportResult
import kotlinx.coroutines.launch

/**
 * A recipe fetched from the web, on its way to the editor for review. Carries the photo separately because
 * it isn't saved anywhere yet — the editor treats it exactly like a freshly picked image.
 */
data class ImportedRecipe(val recipe: ServerRecipe, val imageBytes: ByteArray?)

/**
 * "Import from Web": paste a URL, read the page's schema.org JSON-LD, and hand the result to the recipe
 * editor. This is the simple form of the Swift app's import — the iOS flow rather than the macOS in-app
 * browser with manual field scraping.
 *
 * Nothing is saved here. Imported pages are untrusted content, so the parsed recipe always lands in the
 * editor for the user to look over and save (or discard) themselves.
 */
@Composable
fun WebImportDialog(
    module: AppModule,
    onDismiss: () -> Unit,
    onImported: (ImportedRecipe) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun start() {
        if (url.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            when (val result = runCatching { module.webImporter.import(url) }.getOrElse { WebImportResult.Failed(it.message ?: "Import failed.") }) {
                is WebImportResult.Success -> onImported(ImportedRecipe(result.recipe.toServerRecipe(), result.imageBytes))
                WebImportResult.NoRecipeFound ->
                    error = "That page doesn't publish recipe data Salty can read. Some sites — especially " +
                        "those that build the page in the browser — can't be imported."
                is WebImportResult.Failed -> error = result.message
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import from Web") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Paste a recipe page's address. Salty reads the structured recipe data most recipe " +
                        "sites publish, then opens it for you to review before saving.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Web address") },
                    placeholder = { Text("https://example.com/recipe") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                        platformImeOptions = nativeTextInputImeOptions,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank() && !busy, onClick = { start() }) { Text("Import") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Map a parsed page into the recipe shape the editor works with. The photo is deliberately left off the
 * row: it travels as raw bytes and is written by the editor's own save path, so every thumbnail is
 * generated the same way regardless of where the image came from (matching the Swift importer).
 */
private fun ParsedRecipe.toServerRecipe(): ServerRecipe = ServerRecipe(
    id = newId(),
    name = name,
    source = source.ifBlank { null },
    sourceDetails = sourceDetails.ifBlank { null },
    introduction = introduction.ifBlank { null },
    yield = this.yield.ifBlank { null },
    servings = servings,
    ingredients = ingredients,
    directions = directions,
    preparationTimes = preparationTimes,
)
