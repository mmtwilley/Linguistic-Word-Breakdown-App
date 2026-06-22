package com.lingua_app.backend.repository;

import com.lingua_app.backend.entity.RefreshToken;
import com.lingua_app.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // Derived query: finds a token by its hash value AND only if revokedAt is null.
    // "RevokedAtIsNull" translates to "WHERE revoked_at IS NULL" in SQL.
    // This means revoked tokens stay in the database (for audit) but are never matched here.
    Optional<RefreshToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    // @Transactional is required on repository delete/update methods that are not
    // called from an already-transactional service method. Without it, Spring Data JPA
    // would throw an exception because deletes need an active transaction.
    // Used during logout (delete all sessions for a user) and token rotation.
    @Transactional
    void deleteAllByUser(User user);
}
