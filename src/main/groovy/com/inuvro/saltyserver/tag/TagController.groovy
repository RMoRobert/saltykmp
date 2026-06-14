package com.inuvro.saltyserver.tag

import com.inuvro.saltyserver.model.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/tags")
class TagController {
    private final TagService tagService

    TagController(TagService tagService) {
        this.tagService = tagService
    }

    @GetMapping
    ResponseEntity<List<Tag>> getAllTags() {
        // X-Total-Count is an independent count so clients can detect a truncated/partial
        // response and avoid treating the missing items as deletions.
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(tagService.count()))
                .body(tagService.findAll())
    }

    @GetMapping("/{id}")
    ResponseEntity<Tag> getTagById(@PathVariable("id") String id) {
        def tag = tagService.findById(id)
        return tag.map { new ResponseEntity<>(it, HttpStatus.OK) }
                  .orElse(new ResponseEntity<>(HttpStatus.NOT_FOUND))
    }

    @PostMapping
    ResponseEntity<Tag> createTag(@RequestBody Tag tag) {
        def saved = tagService.save(tag)
        return new ResponseEntity<>(saved, HttpStatus.CREATED)
    }

    @PutMapping("/{id}")
    ResponseEntity<Tag> updateTag(@PathVariable("id") String id, @RequestBody Tag tag) {
        if (!tagService.findById(id).isPresent()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND)
        }
        tag.id = id
        def updated = tagService.save(tag)
        return new ResponseEntity<>(updated, HttpStatus.OK)
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> deleteTag(@PathVariable("id") String id) {
        if (!tagService.findById(id).isPresent()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND)
        }
        tagService.deleteById(id)
        return new ResponseEntity<>(HttpStatus.NO_CONTENT)
    }

    @GetMapping("/search")
    List<Tag> searchTags(@RequestParam("name") String name) {
        return tagService.searchByName(name)
    }
}
