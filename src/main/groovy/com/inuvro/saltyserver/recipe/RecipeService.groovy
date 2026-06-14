package com.inuvro.saltyserver.recipe

import com.inuvro.saltyserver.category.CategoryRepository
import com.inuvro.saltyserver.model.DeviceSync
import com.inuvro.saltyserver.model.Difficulty
import com.inuvro.saltyserver.model.Recipe
import com.inuvro.saltyserver.model.Rating
import com.inuvro.saltyserver.model.User
import com.inuvro.saltyserver.security.CurrentUserService
import com.inuvro.saltyserver.tag.TagRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import java.time.LocalDateTime

@Service
class RecipeService {
    private static final Logger log = LoggerFactory.getLogger(RecipeService)
    
    private final RecipeRepository recipeRepository
    private final DeviceSyncRepository deviceSyncRepository
    private final CategoryRepository categoryRepository
    private final TagRepository tagRepository
    private final CurrentUserService currentUserService

    RecipeService(RecipeRepository recipeRepository, DeviceSyncRepository deviceSyncRepository,
                  CategoryRepository categoryRepository, TagRepository tagRepository,
                  CurrentUserService currentUserService) {
        this.recipeRepository = recipeRepository
        this.deviceSyncRepository = deviceSyncRepository
        this.categoryRepository = categoryRepository
        this.tagRepository = tagRepository
        this.currentUserService = currentUserService
    }

    // ==================== User-Scoped Recipe Methods ====================

    List<Recipe> findAll() {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.findByUserOrderByNameIgnoreCase(user) : []
    }

    /// Independent count for the current user, used as X-Total-Count so clients can detect a
    /// truncated/partial list response before treating missing items as deletions.
    long count() {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.countByUser(user) : 0
    }

    /**
     * Find recipes for the current user with pagination.
     * @return Page of recipes, or empty page if no user
     */
    Page<Recipe> findAll(Pageable pageable) {
        def user = currentUserService.getCurrentUser()
        if (!user) {
            return org.springframework.data.domain.PageImpl.empty(pageable)
        }
        return recipeRepository.findByUserOrderByNameIgnoreCase(user, pageable)
    }

    // ==================== Incremental Sync (delta + manifest) ====================

    /**
     * Lightweight sync index for the current user: every recipe's id + lastModifiedDate (no bodies).
     * The client uses this to reconcile existence/deletions, then fetches only changed bodies via
     * {@link #findForSync}.
     */
    List<RecipeSyncManifestEntry> manifest() {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.findManifestByUser(user) : []
    }

    /**
     * A page of recipe bodies for sync. When {@code modifiedSince} is null this is all of the user's
     * recipes (e.g. first sync); otherwise only those modified after the cutoff (the delta).
     */
    Page<Recipe> findForSync(LocalDateTime modifiedSince, Pageable pageable) {
        def user = currentUserService.getCurrentUser()
        if (!user) {
            return org.springframework.data.domain.PageImpl.empty(pageable)
        }
        return modifiedSince != null
                ? recipeRepository.findByUserAndLastModifiedDateAfterOrderById(user, modifiedSince, pageable)
                : recipeRepository.findByUserOrderById(user, pageable)
    }

    /**
     * Search recipes by name, introduction, or ingredients (case-insensitive contains).
     * @param query search term (trimmed; empty/blank returns empty page)
     * @return Page of matching recipes for the current user
     */
    Page<Recipe> search(String query, Pageable pageable) {
        def user = currentUserService.getCurrentUser()
        if (!user) {
            return org.springframework.data.domain.PageImpl.empty(pageable)
        }
        def term = query?.trim()
        if (!term) {
            return org.springframework.data.domain.PageImpl.empty(pageable)
        }
        def pattern = "%${term}%"
        return recipeRepository.searchByUserAndText(user.id, pattern, pageable)
    }

    Optional<Recipe> findById(String id) {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.findByIdAndUser(id, user) : Optional.empty()
    }

    @Transactional
    Recipe save(Recipe recipe) {
        def user = currentUserService.requireCurrentUser()
        
        // Set user on new recipes
        if (recipe.user == null) {
            recipe.user = user
        }
        
        // Handle categoryIds from JSON input
        def inputCategoryIds = recipe.getInputCategoryIds()
        if (inputCategoryIds != null) {
            def categories = categoryRepository.findAllById(inputCategoryIds)
            recipe.categories = categories.toSet()
            log.debug("Set {} categories for recipe {}", categories.size(), recipe.id)
        }
        
        // Handle tagIds from JSON input
        def inputTagIds = recipe.getInputTagIds()
        if (inputTagIds != null) {
            def tags = tagRepository.findAllById(inputTagIds)
            recipe.tags = tags.toSet()
            log.debug("Set {} tags for recipe {}", tags.size(), recipe.id)
        }
        
        return recipeRepository.save(recipe)
    }

    @Transactional
    void deleteById(String id) {
        def user = currentUserService.getCurrentUser()
        if (user) {
            recipeRepository.deleteByIdAndUser(id, user)
        }
    }
    
    boolean existsById(String id) {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.existsByIdAndUser(id, user) : false
    }

    // ==================== Device Sync Methods ====================
    
    /**
     * Result of device registration
     */
    static class DeviceRegistrationResult {
        DeviceSync device
        boolean isNewDevice  // True only if device was just created
        
        DeviceRegistrationResult(DeviceSync device, boolean isNewDevice) {
            this.device = device
            this.isNewDevice = isNewDevice
        }
    }
    
    /**
     * Get or create a device sync record for the current user
     * Returns both the device and whether it was newly created
     */
    @Transactional
    DeviceRegistrationResult getOrCreateDevice(String deviceId, String deviceName) {
        def user = currentUserService.requireCurrentUser()
        def existing = deviceSyncRepository.findByDeviceIdAndUser(deviceId, user)
        if (existing.isPresent()) {
            // Existing device - NOT a first sync
            return new DeviceRegistrationResult(existing.get(), false)
        }
        // New device - create record, IS a first sync
        def device = new DeviceSync(deviceId, deviceName, user)
        return new DeviceRegistrationResult(deviceSyncRepository.save(device), true)
    }
    
    /**
     * Get device sync info for current user (returns null if device not found)
     */
    Optional<DeviceSync> getDevice(String deviceId) {
        def user = currentUserService.getCurrentUser()
        return user ? deviceSyncRepository.findByDeviceIdAndUser(deviceId, user) : Optional.empty()
    }
    
    /**
     * Update device's last sync time
     */
    @Transactional
    void updateDeviceLastSync(String deviceId) {
        log.info("updateDeviceLastSync called for deviceId: {}", deviceId)
        def user = currentUserService.getCurrentUser()
        if (!user) {
            log.warn("No authenticated user, cannot update lastSyncDate")
            return
        }
        
        def device = deviceSyncRepository.findByDeviceIdAndUser(deviceId, user)
        if (device.isPresent()) {
            def oldDate = device.get().lastSyncDate
            device.get().updateLastSync()
            def newDate = device.get().lastSyncDate
            deviceSyncRepository.save(device.get())
            log.info("Updated device {} lastSyncDate: {} -> {}", deviceId, oldDate, newDate)
        } else {
            log.warn("Device {} not found for user, cannot update lastSyncDate", deviceId)
        }
    }
    
    /**
     * Check if this is a device's first sync for the current user
     */
    boolean isFirstSync(String deviceId) {
        def user = currentUserService.getCurrentUser()
        if (!user) return true
        return !deviceSyncRepository.findByDeviceIdAndUser(deviceId, user).isPresent()
    }
    
    /**
     * Clean up inactive devices (haven't synced in a long time)
     * Returns the number of devices removed
     */
    @Transactional
    int cleanupInactiveDevices(int daysInactive) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysInactive)
        return deviceSyncRepository.deleteByLastSyncDateBefore(cutoff)
    }

    // ==================== Recipe Query Methods (User-Scoped) ====================

    List<Recipe> findByName(String name) {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.findByUserAndName(user, name) : []
    }

    List<Recipe> searchByName(String name) {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.findByUserAndNameContainingIgnoreCase(user, name) : []
    }

    List<Recipe> findFavorites() {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.findByUserAndIsFavorite(user, true) : []
    }

    List<Recipe> findWantToMake() {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.findByUserAndWantToMake(user, true) : []
    }

    List<Recipe> findByCourse(String courseId) {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.findByUserAndCourse_Id(user, courseId) : []
    }

    List<Recipe> findByDifficulty(Difficulty difficulty) {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.findByUserAndDifficulty(user, difficulty) : []
    }

    List<Recipe> findByRating(Rating rating) {
        def user = currentUserService.getCurrentUser()
        return user ? recipeRepository.findByUserAndRating(user, rating) : []
    }
}
