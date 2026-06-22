package com.lingua_app.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter @Setter
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    // FetchType.LAZY means Hibernate does NOT load the User automatically when a
    // RefreshToken is fetched. The User is only queried when you explicitly call
    // token.getUser(). This avoids unnecessary JOIN queries on every token lookup.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // We store a hash of the token value, not the raw token.
    // If the database is compromised, attackers cannot use leaked hashes directly.
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // Null means the token is still valid. Setting revokedAt to a timestamp invalidates
    // it without deleting the record, supporting audit trails and token rotation detection.
    @Column(name = "revoked_at")
    private Instant revokedAt;
}
