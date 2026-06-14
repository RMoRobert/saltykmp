package com.inuvro.saltyserver.course

import com.inuvro.saltyserver.model.Course
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/courses")
class CourseController {
    private final CourseService courseService

    CourseController(CourseService courseService) {
        this.courseService = courseService
    }

    @GetMapping
    ResponseEntity<List<Course>> getAllCourses() {
        // X-Total-Count is an independent count so clients can detect a truncated/partial
        // response and avoid treating the missing items as deletions.
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(courseService.count()))
                .body(courseService.findAll())
    }

    @GetMapping("/{id}")
    ResponseEntity<Course> getCourseById(@PathVariable("id") String id) {
        def course = courseService.findById(id)
        return course.map { new ResponseEntity<>(it, HttpStatus.OK) }
                     .orElse(new ResponseEntity<>(HttpStatus.NOT_FOUND))
    }

    @PostMapping
    ResponseEntity<Course> createCourse(@RequestBody Course course) {
        def saved = courseService.save(course)
        return new ResponseEntity<>(saved, HttpStatus.CREATED)
    }

    @PutMapping("/{id}")
    ResponseEntity<Course> updateCourse(@PathVariable("id") String id, @RequestBody Course course) {
        if (!courseService.findById(id).isPresent()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND)
        }
        course.id = id
        def updated = courseService.save(course)
        return new ResponseEntity<>(updated, HttpStatus.OK)
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> deleteCourse(@PathVariable("id") String id) {
        if (!courseService.findById(id).isPresent()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND)
        }
        courseService.deleteById(id)
        return new ResponseEntity<>(HttpStatus.NO_CONTENT)
    }

    @GetMapping("/search")
    List<Course> searchCourses(@RequestParam("name") String name) {
        return courseService.searchByName(name)
    }
}
