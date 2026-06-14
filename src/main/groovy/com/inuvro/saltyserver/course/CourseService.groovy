package com.inuvro.saltyserver.course

import com.inuvro.saltyserver.model.Course
import com.inuvro.saltyserver.security.CurrentUserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CourseService {
    private final CourseRepository courseRepository
    private final CurrentUserService currentUserService

    CourseService(CourseRepository courseRepository, CurrentUserService currentUserService) {
        this.courseRepository = courseRepository
        this.currentUserService = currentUserService
    }

    List<Course> findAll() {
        def user = currentUserService.getCurrentUser()
        return user ? courseRepository.findByUser(user) : []
    }

    /// Independent count for the current user, used as X-Total-Count so clients can detect a
    /// truncated/partial list response before treating missing items as deletions.
    long count() {
        def user = currentUserService.getCurrentUser()
        return user ? courseRepository.countByUser(user) : 0
    }

    Optional<Course> findById(String id) {
        def user = currentUserService.getCurrentUser()
        return user ? courseRepository.findByIdAndUser(id, user) : Optional.empty()
    }

    @Transactional
    Course save(Course course) {
        def user = currentUserService.requireCurrentUser()
        if (course.user == null) {
            course.user = user
        }
        return courseRepository.save(course)
    }

    @Transactional
    void deleteById(String id) {
        def user = currentUserService.getCurrentUser()
        if (user) {
            courseRepository.deleteByIdAndUser(id, user)
        }
    }

    List<Course> findByName(String name) {
        def user = currentUserService.getCurrentUser()
        return user ? courseRepository.findByUserAndName(user, name) : []
    }

    List<Course> searchByName(String name) {
        def user = currentUserService.getCurrentUser()
        return user ? courseRepository.findByUserAndNameContainingIgnoreCase(user, name) : []
    }
}
