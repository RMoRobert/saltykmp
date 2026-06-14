package com.inuvro.saltyserver.course

import com.inuvro.saltyserver.model.Course
import com.inuvro.saltyserver.model.User
import org.springframework.data.jpa.repository.JpaRepository

interface CourseRepository extends JpaRepository<Course, String> {
    // User-scoped queries
    List<Course> findByUser(User user)
    long countByUser(User user)
    Optional<Course> findByIdAndUser(String id, User user)
    boolean existsByIdAndUser(String id, User user)
    void deleteByIdAndUser(String id, User user)
    
    // User-scoped search
    List<Course> findByUserAndName(User user, String name)
    List<Course> findByUserAndNameContainingIgnoreCase(User user, String name)
    
    // Legacy methods
    List<Course> findByName(String name)
    List<Course> findByNameContainingIgnoreCase(String name)
}
