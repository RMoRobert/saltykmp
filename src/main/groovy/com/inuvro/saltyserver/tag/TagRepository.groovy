package com.inuvro.saltyserver.tag

import com.inuvro.saltyserver.model.Tag
import com.inuvro.saltyserver.model.User
import org.springframework.data.jpa.repository.JpaRepository

interface TagRepository extends JpaRepository<Tag, String> {
    // User-scoped queries
    List<Tag> findByUser(User user)
    long countByUser(User user)
    Optional<Tag> findByIdAndUser(String id, User user)
    boolean existsByIdAndUser(String id, User user)
    void deleteByIdAndUser(String id, User user)
    
    // User-scoped search
    List<Tag> findByUserAndName(User user, String name)
    List<Tag> findByUserAndNameContainingIgnoreCase(User user, String name)
    
    // Legacy methods
    List<Tag> findByName(String name)
    List<Tag> findByNameContainingIgnoreCase(String name)
}
