package com.inuvro.saltyserver.category

import com.inuvro.saltyserver.model.Category
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/categories")
class CategoryController {
    private final CategoryService categoryService

    CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService
    }

    @GetMapping
    ResponseEntity<List<Category>> getAllCategories() {
        // X-Total-Count is an independent count so clients can detect a truncated/partial
        // response and avoid treating the missing items as deletions.
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(categoryService.count()))
                .body(categoryService.findAll())
    }

    @GetMapping("/{id}")
    ResponseEntity<Category> getCategoryById(@PathVariable("id") String id) {
        def category = categoryService.findById(id)
        return category.map { new ResponseEntity<>(it, HttpStatus.OK) }
                      .orElse(new ResponseEntity<>(HttpStatus.NOT_FOUND))
    }

    @PostMapping
    ResponseEntity<Category> createCategory(@RequestBody Category category) {
        def saved = categoryService.save(category)
        return new ResponseEntity<>(saved, HttpStatus.CREATED)
    }

    @PutMapping("/{id}")
    ResponseEntity<Category> updateCategory(@PathVariable("id") String id, @RequestBody Category category) {
        if (!categoryService.findById(id).isPresent()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND)
        }
        category.id = id
        def updated = categoryService.save(category)
        return new ResponseEntity<>(updated, HttpStatus.OK)
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> deleteCategory(@PathVariable("id") String id) {
        if (!categoryService.findById(id).isPresent()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND)
        }
        categoryService.deleteById(id)
        return new ResponseEntity<>(HttpStatus.NO_CONTENT)
    }

    @GetMapping("/search")
    List<Category> searchCategories(@RequestParam("name") String name) {
        return categoryService.searchByName(name)
    }
}
