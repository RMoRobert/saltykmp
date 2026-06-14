package com.inuvro.saltyserver.recipe

import com.inuvro.saltyserver.model.Difficulty
import com.inuvro.saltyserver.model.Recipe
import com.inuvro.saltyserver.model.Rating
import com.inuvro.saltyserver.model.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

import java.time.LocalDateTime

interface RecipeRepository extends JpaRepository<Recipe, String> {
    // Spring Data JPA automatically provides CRUD operations

    // User-scoped queries
    List<Recipe> findByUser(User user)
    long countByUser(User user)

    // ---- Incremental sync (delta + manifest) ----

    /** Lightweight sync index: id + lastModifiedDate only (no CLOB bodies loaded). */
    @Query("SELECT new com.inuvro.saltyserver.recipe.RecipeSyncManifestEntry(r.id, r.lastModifiedDate) FROM Recipe r WHERE r.user = :user")
    List<RecipeSyncManifestEntry> findManifestByUser(@Param("user") User user)

    /** All of the user's recipes, paged, stable order by id. */
    Page<Recipe> findByUserOrderById(User user, Pageable pageable)

    /** Recipes modified after the cutoff (the `modifiedSince` delta), paged, stable order by id. */
    Page<Recipe> findByUserAndLastModifiedDateAfterOrderById(User user, LocalDateTime date, Pageable pageable)

    @Query("SELECT r FROM Recipe r WHERE r.user = :user ORDER BY LOWER(r.name)")
    List<Recipe> findByUserOrderByNameIgnoreCase(@Param("user") User user)

    @Query("SELECT r FROM Recipe r WHERE r.user = :user ORDER BY LOWER(r.name)")
    Page<Recipe> findByUserOrderByNameIgnoreCase(@Param("user") User user, Pageable pageable)
    Optional<Recipe> findByIdAndUser(String id, User user)
    boolean existsByIdAndUser(String id, User user)
    void deleteByIdAndUser(String id, User user)
    
    // Custom query methods (user-scoped)
    List<Recipe> findByUserAndName(User user, String name)
    List<Recipe> findByUserAndNameContainingIgnoreCase(User user, String name)
    List<Recipe> findByUserAndIsFavorite(User user, Boolean isFavorite)
    List<Recipe> findByUserAndWantToMake(User user, Boolean wantToMake)
    List<Recipe> findByUserAndCourse_Id(User user, String courseId)
    List<Recipe> findByUserAndDifficulty(User user, Difficulty difficulty)
    List<Recipe> findByUserAndRating(User user, Rating rating)
    
    /**
     * Search recipes by name, introduction, or ingredients (user-scoped, paginated).
     * Pattern should be like %term% for contains search.
     * Uses quoted identifiers for H2 (migration creates "recipe" etc.).
     */
    @Query(value = """
        SELECT * FROM "recipe" r
        WHERE r."user_id" = :userId
        AND (
            LOWER(r."name") LIKE LOWER(:pattern)
            OR (r."introduction" IS NOT NULL AND LOWER(r."introduction") LIKE LOWER(:pattern))
            OR (r."ingredients" IS NOT NULL AND LOWER(CAST(r."ingredients" AS VARCHAR(100000))) LIKE LOWER(:pattern))
        )
        ORDER BY LOWER(r."name")
        """,
        countQuery = """
        SELECT COUNT(*) FROM "recipe" r
        WHERE r."user_id" = :userId
        AND (
            LOWER(r."name") LIKE LOWER(:pattern)
            OR (r."introduction" IS NOT NULL AND LOWER(r."introduction") LIKE LOWER(:pattern))
            OR (r."ingredients" IS NOT NULL AND LOWER(CAST(r."ingredients" AS VARCHAR(100000))) LIKE LOWER(:pattern))
        )
        """,
        nativeQuery = true)
    Page<Recipe> searchByUserAndText(@Param("userId") String userId, @Param("pattern") String pattern, Pageable pageable)

    // Legacy methods (for backwards compatibility during migration)
    List<Recipe> findByName(String name)
    List<Recipe> findByNameContainingIgnoreCase(String name)
    List<Recipe> findByIsFavorite(Boolean isFavorite)
    List<Recipe> findByWantToMake(Boolean wantToMake)
    List<Recipe> findByCourse_Id(String courseId)
    List<Recipe> findByDifficulty(Difficulty difficulty)
    List<Recipe> findByRating(Rating rating)
}
