package com.inuvro.saltyserver.category

import com.inuvro.saltyserver.model.Category
import com.inuvro.saltyserver.model.User
import org.springframework.data.jpa.repository.JpaRepository

interface CategoryRepository extends JpaRepository<Category, String> {
    // User-scoped queries
    List<Category> findByUser(User user)
    long countByUser(User user)
    Optional<Category> findByIdAndUser(String id, User user)
    boolean existsByIdAndUser(String id, User user)
    void deleteByIdAndUser(String id, User user)
    
    // User-scoped search
    List<Category> findByUserAndName(User user, String name)
    List<Category> findByUserAndNameContainingIgnoreCase(User user, String name)
    
    // Legacy methods
    List<Category> findByName(String name)
    List<Category> findByNameContainingIgnoreCase(String name)
}
