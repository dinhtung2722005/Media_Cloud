package com.cloudmedia.api.entity;

import jakarta.persistence.*;
import java.util.Date;

@Entity
@Table(name = "auth_sessions", indexes = @Index(name = "idx_auth_session_expiry", columnList = "expires_at"))
public class AuthSession {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "expires_at", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    protected AuthSession() {}

    public AuthSession(String id, String userId, Date expiresAt) {
        this.id = id;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public Date getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
}
