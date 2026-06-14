package com.inuvro.saltyserver.recipe

import java.time.LocalDateTime

/**
 * Lightweight sync index entry: just a recipe's id and last-modified timestamp.
 *
 * The Salty client fetches the full manifest (all of the user's recipes) to reconcile existence and
 * detect deletions, while fetching only changed recipe *bodies* via the paginated `modifiedSince`
 * delta. Loading just these two columns (a JPQL constructor projection) avoids reading the large
 * directions/ingredients/notes CLOBs.
 */
class RecipeSyncManifestEntry {
    String id
    LocalDateTime lastModifiedDate

    RecipeSyncManifestEntry(String id, LocalDateTime lastModifiedDate) {
        this.id = id
        this.lastModifiedDate = lastModifiedDate
    }
}
