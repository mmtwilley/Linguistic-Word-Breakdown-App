package com.lingua_app.backend.repository;

import com.lingua_app.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

// JpaRepository<User, UUID> provides standard CRUD operations for free:
//   save(), findById(), findAll(), delete(), existsById(), count(), etc.
// The second type parameter (UUID) is the primary key type.
//
// Spring Data JPA generates the SQL implementation at startup from the method names below —
// no SQL or JPQL needs to be written manually for simple queries.
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // Derived query: Spring reads the method name and generates:
    //   SELECT * FROM users WHERE email = ? LIMIT 1
    // Returns Optional so callers are forced to handle the "not found" case explicitly.
    Optional<User> findByEmail(String email);

    // Returns true/false without loading the full entity — more efficient than
    // findByEmail(...).isPresent() during registration duplicate checks.
    boolean existsByEmail(String email);
}
