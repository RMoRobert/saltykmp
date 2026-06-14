package com.inuvro.saltyserver

import com.inuvro.saltyserver.category.CategoryService
import com.inuvro.saltyserver.course.CourseService
import com.inuvro.saltyserver.model.Category
import com.inuvro.saltyserver.model.Course
import com.inuvro.saltyserver.model.Recipe
import com.inuvro.saltyserver.model.Tag
import com.inuvro.saltyserver.recipe.RecipeService
import com.inuvro.saltyserver.tag.TagService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc

import static org.mockito.Mockito.when
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Verifies the list endpoints emit an `X-Total-Count` header derived from an INDEPENDENT count
 * (service.count()), so the Salty client can detect a truncated/partial response and avoid
 * treating missing items as deletions.
 *
 * Security filters are disabled (addFilters = false) and the services are mocked, so these tests
 * isolate the controller -> header wiring without needing JWT auth or the database.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class ListEndpointTotalCountTests {

    @Autowired
    MockMvc mockMvc

    @MockitoBean
    CourseService courseService
    @MockitoBean
    CategoryService categoryService
    @MockitoBean
    TagService tagService
    @MockitoBean
    RecipeService recipeService

    @Test
    void coursesEndpointReportsTotalCount() {
        when(courseService.findAll()).thenReturn([new Course("c1", "Main"), new Course("c2", "Dessert")])
        when(courseService.count()).thenReturn(2L)

        mockMvc.perform(get("/api/courses"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath('$.length()').value(2))
    }

    @Test
    void categoriesEndpointReportsTotalCount() {
        when(categoryService.findAll()).thenReturn([new Category("cat1", "Breads")])
        when(categoryService.count()).thenReturn(1L)

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath('$.length()').value(1))
    }

    @Test
    void tagsEndpointReportsTotalCount() {
        when(tagService.findAll()).thenReturn([new Tag("t1", "spicy"), new Tag("t2", "quick"), new Tag("t3", "vegan")])
        when(tagService.count()).thenReturn(3L)

        mockMvc.perform(get("/api/tags"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(jsonPath('$.length()').value(3))
    }

    @Test
    void recipesEndpointReportsTotalCount() {
        when(recipeService.findAll()).thenReturn([new Recipe(id: "r1", name: "Pancakes")])
        when(recipeService.count()).thenReturn(1L)

        mockMvc.perform(get("/api/recipes"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath('$.length()').value(1))
    }

    @Test
    void emptyListReportsZeroTotalCount() {
        when(courseService.findAll()).thenReturn([])
        when(courseService.count()).thenReturn(0L)

        mockMvc.perform(get("/api/courses"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(jsonPath('$.length()').value(0))
    }

    @Test
    void headerReflectsIndependentCountNotListSize() {
        // The whole point: simulate a truncated list (e.g., a future pagination/limit regression)
        // where findAll() returns fewer rows than the independent count(). The header must report
        // the count, letting the client see body.count (1) != X-Total-Count (5) and abort the sync.
        when(recipeService.findAll()).thenReturn([new Recipe(id: "r1", name: "Only One Returned")])
        when(recipeService.count()).thenReturn(5L)

        mockMvc.perform(get("/api/recipes"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(jsonPath('$.length()').value(1))
    }
}
