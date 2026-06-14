package com.inuvro.saltyserver.recipe

import com.inuvro.saltyserver.image.ImageStorageService
import com.inuvro.saltyserver.model.Recipe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc

import java.time.LocalDateTime

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.isNull
import static org.mockito.Mockito.when
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Verifies the incremental-sync surface on GET /api/recipes:
 *   - legacy no-param behavior (all recipes + independent X-Total-Count)
 *   - the `modifiedSince` delta (only changed recipes)
 *   - pagination (page/size + X-Total-Count/X-Total-Pages/X-Page-Number)
 *   - the lightweight GET /api/recipes/sync/manifest
 *   - rejection of an unparseable `modifiedSince`
 *
 * Security filters are disabled and the service is mocked, isolating controller wiring (the JPQL
 * projection / user-scoping are validated by Spring context startup + the repository's by-user queries).
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class RecipeSyncDeltaTests {

    @Autowired
    MockMvc mockMvc

    @MockitoBean
    RecipeService recipeService
    @MockitoBean
    ImageStorageService imageStorageService

    @Test
    void noParamsReturnsAllWithTotalCount() {
        when(recipeService.findAll()).thenReturn([
                new Recipe(id: "r1", name: "Pancakes"),
                new Recipe(id: "r2", name: "Waffles")
        ])
        when(recipeService.count()).thenReturn(2L)

        mockMvc.perform(get("/api/recipes"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath('$.length()').value(2))
    }

    @Test
    void modifiedSinceReturnsOnlyTheDelta() {
        // Only the changed recipe comes back; X-Total-Count reflects the matching count, not the whole table.
        when(recipeService.findForSync(any(LocalDateTime), any()))
                .thenReturn(new PageImpl<Recipe>([new Recipe(id: "r2", name: "Waffles")]))

        mockMvc.perform(get("/api/recipes").param("modifiedSince", "2026-06-13T21:43:10.000Z"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath('$.length()').value(1))
                .andExpect(jsonPath('$[0].id').value("r2"))
    }

    @Test
    void paginationReturnsPageAndHeaders() {
        // Page 0 of size 2 out of 5 total matching → 3 pages.
        def page = new PageImpl<Recipe>(
                [new Recipe(id: "r1", name: "Pancakes")],
                PageRequest.of(0, 2),
                5L)
        when(recipeService.findForSync(isNull(), any())).thenReturn(page)

        mockMvc.perform(get("/api/recipes").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(header().string("X-Total-Pages", "3"))
                .andExpect(header().string("X-Page-Number", "0"))
                .andExpect(jsonPath('$.length()').value(1))
    }

    @Test
    void manifestReturnsIdAndTimestampForEachRecipe() {
        when(recipeService.manifest()).thenReturn([
                new RecipeSyncManifestEntry("r1", LocalDateTime.parse("2026-06-13T10:00:00")),
                new RecipeSyncManifestEntry("r2", LocalDateTime.parse("2026-06-14T11:30:00"))
        ])

        mockMvc.perform(get("/api/recipes/sync/manifest"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath('$.length()').value(2))
                .andExpect(jsonPath('$[0].id').value("r1"))
                .andExpect(jsonPath('$[0].lastModifiedDate').exists())
    }

    @Test
    void unparseableModifiedSinceReturns400() {
        mockMvc.perform(get("/api/recipes").param("modifiedSince", "not-a-date"))
                .andExpect(status().isBadRequest())
    }
}
