package com.inuvro.saltyserver.recipe

import com.inuvro.saltyserver.image.ImageStorageService
import com.inuvro.saltyserver.model.Recipe
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/recipes")
class RecipeController {
    private static final Logger log = LoggerFactory.getLogger(RecipeController)
    private static final int DEFAULT_PAGE_SIZE = 100

    private final RecipeService recipeService
    private final ImageStorageService imageStorageService

    RecipeController(RecipeService recipeService, ImageStorageService imageStorageService) {
        this.recipeService = recipeService
        this.imageStorageService = imageStorageService
    }

    /**
     * List recipes for sync.
     *
     * - No params: all of the user's recipes (legacy behavior), with X-Total-Count = total.
     * - {@code modifiedSince} (ISO-8601, e.g. 2026-06-13T21:43:10.000Z): only recipes modified after
     *   that instant (the delta). X-Total-Count = number of matching recipes.
     * - {@code page} (+ optional {@code size}): returns that page of the (optionally filtered) set,
     *   with X-Total-Count / X-Total-Pages / X-Page-Number headers so the client can page through.
     *
     * The delta deliberately omits unchanged recipes, so clients MUST reconcile deletions against the
     * lightweight full manifest (GET /sync/manifest), never against this list.
     */
    @GetMapping
    ResponseEntity<List<Recipe>> getAllRecipes(
            @RequestParam(name = "modifiedSince", required = false) String modifiedSince,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {

        LocalDateTime since = null
        if (modifiedSince != null && !modifiedSince.isEmpty()) {
            try {
                since = parseModifiedSince(modifiedSince)
            } catch (Exception e) {
                log.warn("Rejecting unparseable modifiedSince '{}': {}", modifiedSince, e.message)
                return ResponseEntity.badRequest()
                        .header("X-Error", "Invalid modifiedSince (expected ISO-8601)")
                        .build()
            }
        }

        // Paginated branch: return one page + paging headers.
        if (page != null) {
            int pageNum = Math.max(0, page)
            int pageSize = (size != null && size > 0) ? size : DEFAULT_PAGE_SIZE
            def result = recipeService.findForSync(since, PageRequest.of(pageNum, pageSize))
            return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(result.totalElements))
                    .header("X-Total-Pages", String.valueOf(result.totalPages))
                    .header("X-Page-Number", String.valueOf(result.number))
                    .body(result.content)
        }

        // Non-paginated delta: all matching recipes in one response.
        if (since != null) {
            def matching = recipeService.findForSync(since, org.springframework.data.domain.Pageable.unpaged()).content
            return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(matching.size()))
                    .body(matching)
        }

        // Legacy: all recipes, with an independent X-Total-Count for truncation detection.
        def recipes = recipeService.findAll()
        recipes.each { recipe ->
            log.debug("Returning recipe '{}' with categoryIds: {}, tagIds: {}",
                recipe.name, recipe.getCategoryIds(), recipe.getTagIds())
        }
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(recipeService.count()))
                .body(recipes)
    }

    /**
     * Lightweight sync index: id + lastModifiedDate for every recipe of the current user.
     * Clients diff this against their local DB to decide what to download (via the modifiedSince
     * delta) and what to delete. X-Total-Count lets clients detect a truncated response.
     */
    @GetMapping("/sync/manifest")
    ResponseEntity<List<RecipeSyncManifestEntry>> getRecipeManifest() {
        def manifest = recipeService.manifest()
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(manifest.size()))
                .body(manifest)
    }

    /** Parse an ISO-8601 instant ("...Z") or local date-time into a UTC LocalDateTime. */
    private static LocalDateTime parseModifiedSince(String s) {
        try {
            return LocalDateTime.ofInstant(Instant.parse(s), ZoneOffset.UTC)
        } catch (Exception ignored) {
            return LocalDateTime.parse(s) // ISO_LOCAL_DATE_TIME fallback; throws if also invalid
        }
    }

    @GetMapping("/{id}")
    ResponseEntity<Recipe> getRecipeById(@PathVariable("id") String id) {
        def recipe = recipeService.findById(id)
        return recipe.map { new ResponseEntity<>(it, HttpStatus.OK) }
                    .orElse(new ResponseEntity<>(HttpStatus.NOT_FOUND))
    }

    @PostMapping
    ResponseEntity<Recipe> createRecipe(@RequestBody Recipe recipe) {
        log.info("Creating recipe '{}' with categoryIds: {}, tagIds: {}", 
            recipe.name, recipe.getInputCategoryIds(), recipe.getInputTagIds())
        def saved = recipeService.save(recipe)
        log.info("Saved recipe '{}' with {} categories, {} tags", 
            saved.name, saved.categories?.size() ?: 0, saved.tags?.size() ?: 0)
        return new ResponseEntity<>(saved, HttpStatus.CREATED)
    }

    @PutMapping("/{id}")
    ResponseEntity<Recipe> updateRecipe(@PathVariable("id") String id, @RequestBody Recipe recipe) {
        if (!recipeService.findById(id).isPresent()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND)
        }
        recipe.id = id
        log.info("Updating recipe '{}' with categoryIds: {}, tagIds: {}", 
            recipe.name, recipe.getInputCategoryIds(), recipe.getInputTagIds())
        def updated = recipeService.save(recipe)
        log.info("Updated recipe '{}' with {} categories, {} tags", 
            updated.name, updated.categories?.size() ?: 0, updated.tags?.size() ?: 0)
        return new ResponseEntity<>(updated, HttpStatus.OK)
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> deleteRecipe(@PathVariable("id") String id) {
        if (!recipeService.findById(id).isPresent()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND)
        }
        recipeService.deleteById(id)
        return new ResponseEntity<>(HttpStatus.NO_CONTENT)
    }

    @GetMapping("/search")
    List<Recipe> searchRecipes(@RequestParam(required = false) String name,
                                @RequestParam(required = false) Boolean favorite,
                                @RequestParam(required = false) Boolean wantToMake,
                                @RequestParam(required = false) String courseId) {
        if (name != null) {
            return recipeService.searchByName(name)
        }
        if (favorite != null && favorite) {
            return recipeService.findFavorites()
        }
        if (wantToMake != null && wantToMake) {
            return recipeService.findWantToMake()
        }
        if (courseId != null) {
            return recipeService.findByCourse(courseId)
        }
        return recipeService.findAll()
    }

    /**
     * Upload an image for a recipe.
     * POST /api/recipes/{id}/image
     */
    @PostMapping("/{id}/image")
    ResponseEntity<Map<String, String>> uploadRecipeImage(
            @PathVariable("id") String id,
            @RequestParam("file") MultipartFile file) {
        
        def recipeOpt = recipeService.findById(id)
        if (!recipeOpt.isPresent()) {
            return ResponseEntity.notFound().build()
        }
        
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body([error: "No file provided"])
        }
        
        // Validate file type
        String contentType = file.getContentType()
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest()
                    .body([error: "Invalid file type. Must be an image."])
        }
        
        try {
            Recipe recipe = recipeOpt.get()
            
            // Delete old image if exists
            if (recipe.imageFilename) {
                imageStorageService.delete(recipe.imageFilename)
            }
            
            // Store new image using recipe ID as base filename
            String filename = imageStorageService.storeForRecipe(file, id)
            
            // Update recipe with new filename
            recipe.imageFilename = filename
            recipeService.save(recipe)
            
            return ResponseEntity.ok([
                filename: filename,
                url: "/api/recipes/images/${filename}".toString()
            ])
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body([error: "Failed to upload image: ${e.message}".toString()])
        }
    }

    /**
     * Delete the image for a recipe.
     * DELETE /api/recipes/{id}/image
     */
    @DeleteMapping("/{id}/image")
    ResponseEntity<Void> deleteRecipeImage(@PathVariable("id") String id) {
        def recipeOpt = recipeService.findById(id)
        if (!recipeOpt.isPresent()) {
            return ResponseEntity.notFound().build()
        }
        
        Recipe recipe = recipeOpt.get()
        
        if (recipe.imageFilename) {
            imageStorageService.delete(recipe.imageFilename)
            recipe.imageFilename = null
            recipeService.save(recipe)
        }
        
        return ResponseEntity.noContent().build()
    }

    /**
     * Get the image for a recipe (redirects to /api/recipes/images/{filename}).
     * GET /api/recipes/{id}/image
     */
    @GetMapping("/{id}/image")
    ResponseEntity<Void> getRecipeImage(@PathVariable("id") String id) {
        def recipeOpt = recipeService.findById(id)
        if (!recipeOpt.isPresent()) {
            return ResponseEntity.notFound().build()
        }
        
        Recipe recipe = recipeOpt.get()
        
        if (!recipe.imageFilename || !imageStorageService.exists(recipe.imageFilename)) {
            return ResponseEntity.notFound().build()
        }
        
        // Redirect to the image endpoint
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "/api/recipes/images/${recipe.imageFilename}".toString())
                .build()
    }
    
    // ==================== Device Sync ====================
    
    /**
     * Register or update a device for sync.
     * POST /api/sync/device
     * Body: { deviceId: "uuid", deviceName: "iPhone" }
     * Returns device info including lastSyncDate
     */
    @PostMapping("/sync/device")
    ResponseEntity<Map<String, Object>> registerDevice(@RequestBody Map<String, String> body) {
        String deviceId = body.get("deviceId")
        String deviceName = body.get("deviceName") ?: "Unknown Device"
        
        if (deviceId == null || deviceId.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body([error: "deviceId is required"])
        }
        
        def result = recipeService.getOrCreateDevice(deviceId, deviceName)
        def device = result.device
        
        log.info("Device registration: deviceId={}, isNewDevice={}, lastSyncDate={}, firstSyncDate={}", 
            deviceId, result.isNewDevice, device.lastSyncDate, device.firstSyncDate)
        
        return ResponseEntity.ok([
            deviceId: device.deviceId,
            deviceName: device.deviceName,
            lastSyncDate: device.lastSyncDate,
            firstSyncDate: device.firstSyncDate,
            isFirstSync: result.isNewDevice  // Only true if device was JUST created
        ])
    }
    
    /**
     * Get device sync info.
     * GET /api/sync/device/{deviceId}
     */
    @GetMapping("/sync/device/{deviceId}")
    ResponseEntity<Map<String, Object>> getDeviceInfo(@PathVariable("deviceId") String deviceId) {
        def deviceOpt = recipeService.getDevice(deviceId)
        if (!deviceOpt.isPresent()) {
            return ResponseEntity.ok([
                isFirstSync: true
            ])
        }
        
        def device = deviceOpt.get()
        return ResponseEntity.ok([
            deviceId: device.deviceId,
            deviceName: device.deviceName,
            lastSyncDate: device.lastSyncDate,
            firstSyncDate: device.firstSyncDate,
            isFirstSync: false
        ])
    }
    
    /**
     * Mark sync as complete - updates device's lastSyncDate.
     * POST /api/sync/device/{deviceId}/complete
     */
    @PostMapping("/sync/device/{deviceId}/complete")
    ResponseEntity<Void> completeSyncForDevice(@PathVariable("deviceId") String deviceId) {
        log.info("completeSyncForDevice called with deviceId: {}", deviceId)
        recipeService.updateDeviceLastSync(deviceId)
        return ResponseEntity.ok().build()
    }
    
    /**
     * Perform sync for a device - returns recipes and deletion info.
     * POST /api/sync/{deviceId}
     * Body: { recipeIds: ["id1", "id2", ...] } - list of recipe IDs the client has
     * Returns: { 
     *   toDownload: [...], // recipes to download (new or updated on server)
     *   toDelete: [...],   // recipe IDs to delete locally (deleted on server)
     *   toUpload: [...],   // recipe IDs the server doesn't have (client should upload)
     *   serverShouldDelete: [...] // recipe IDs that were deleted on client
     * }
     */
    @PostMapping("/sync/{deviceId}")
    ResponseEntity<Map<String, Object>> syncRecipes(
            @PathVariable("deviceId") String deviceId,
            @RequestBody Map<String, Object> body) {
        
        // Get or create device
        String deviceName = body.get("deviceName")?.toString() ?: "Unknown Device"
        def result = recipeService.getOrCreateDevice(deviceId, deviceName)
        def device = result.device
        def lastSyncDate = device.lastSyncDate
        def isFirstSync = result.isNewDevice  // Only true if device was JUST created
        
        // Get client's recipe IDs and their last modified dates
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clientRecipes = (List<Map<String, Object>>) body.get("recipes") ?: []
        Map<String, String> clientRecipeModDates = [:]
        Set<String> clientRecipeIds = [] as Set
        
        for (Map<String, Object> recipe : clientRecipes) {
            String id = recipe.get("id")?.toString()
            String lastMod = recipe.get("lastModifiedDate")?.toString()
            if (id) {
                clientRecipeIds.add(id)
                if (lastMod) {
                    clientRecipeModDates[id] = lastMod
                }
            }
        }
        
        // Get all server recipes
        def serverRecipes = recipeService.findAll()
        Map<String, Recipe> serverRecipesById = serverRecipes.collectEntries { [it.id, it] }
        Set<String> serverRecipeIds = serverRecipesById.keySet()
        
        // Determine what to sync
        List<Recipe> toDownload = []
        List<String> toDelete = []
        List<String> toUpload = []
        List<String> serverShouldDelete = []
        
        // Recipes on server but not on client
        for (Recipe serverRecipe : serverRecipes) {
            if (!clientRecipeIds.contains(serverRecipe.id)) {
                if (isFirstSync) {
                    // First sync - download everything from server
                    toDownload.add(serverRecipe)
                } else {
                    // Not first sync - check if new or deleted
                    if (serverRecipe.lastModifiedDate != null && 
                        serverRecipe.lastModifiedDate.isAfter(lastSyncDate)) {
                        // Recipe created/modified after last sync - download
                        toDownload.add(serverRecipe)
                    } else {
                        // Recipe existed before last sync but client doesn't have it
                        // Client deleted it - delete from server
                        serverShouldDelete.add(serverRecipe.id)
                    }
                }
            }
        }
        
        // Recipes on client but not on server
        for (String clientId : clientRecipeIds) {
            if (!serverRecipeIds.contains(clientId)) {
                if (isFirstSync) {
                    // First sync - upload everything
                    toUpload.add(clientId)
                } else {
                    // Not first sync - check if new or deleted
                    String lastModStr = clientRecipeModDates[clientId]
                    // If client recipe is newer than last sync, it's new - upload
                    // Otherwise, it was deleted on server - tell client to delete
                    // For simplicity, we'll tell client to upload and let it decide
                    toUpload.add(clientId)
                }
            }
        }
        
        // Recipes on both - check for updates (handled by existing sync logic)
        // The client will compare lastModifiedDate for recipes it has
        
        return ResponseEntity.ok([
            toDownload: toDownload,
            toDelete: toDelete,
            toUpload: toUpload,
            serverShouldDelete: serverShouldDelete,
            isFirstSync: isFirstSync,
            lastSyncDate: lastSyncDate
        ])
    }
    
    /**
     * Delete recipes that were deleted on a client device.
     * POST /api/sync/delete
     * Body: { deviceId: "...", recipeIds: ["id1", "id2"] }
     */
    @PostMapping("/sync/delete")
    ResponseEntity<Map<String, Object>> deleteRecipesFromClient(@RequestBody Map<String, Object> body) {
        String deviceId = body.get("deviceId")?.toString()
        @SuppressWarnings("unchecked")
        List<String> recipeIds = (List<String>) body.get("recipeIds") ?: []
        
        int deleted = 0
        for (String id : recipeIds) {
            if (recipeService.existsById(id)) {
                // Delete recipe and its image
                def recipeOpt = recipeService.findById(id)
                if (recipeOpt.isPresent() && recipeOpt.get().imageFilename) {
                    imageStorageService.delete(recipeOpt.get().imageFilename)
                }
                recipeService.deleteById(id)
                deleted++
            }
        }
        
        return ResponseEntity.ok([deleted: deleted])
    }
}
